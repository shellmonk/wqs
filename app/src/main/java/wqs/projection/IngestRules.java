package wqs.projection;

import java.util.EnumSet;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import wqs.domain.ProjectionTransaction;
import wqs.domain.ViewKind;

/**
 * The per-transaction ingest rules that reject a transaction outright (spec 4.1). A rejected transaction is
 * dead-lettered by the writer and nothing of it is written -- not even its locator row -- while
 * the rest of its batch applies.
 * <ul>
 *   <li>{@code einstant-unsessioned} (plan D7, spec 7.4): a transaction whose product type is eInstant
 *       ({@link EInstantProperties}) and that has no batchId. The ingest half of P-7.</li>
 *   <li>{@code sessioned-source-not-on-every-view} (Task 3 ruling): a sessioned transaction whose
 *       source does not feed every view. Sessions are shared by both endpoints, so a session may
 *       hold only sources that feed both.</li>
 *   <li>{@code currency-mismatch} (plan D6, O-4): a sessioned transaction whose currency differs from its
 *       existing session's. A session is single-currency, so its sums mean something.</li>
 * </ul>
 * A cancellation is exempt from every rule. It never rolls up and never gets a page-index row,
 * so it can neither mix a session's currency nor put anything into the eInstant view's
 * unsessioned set -- P-7 constrains the view's non-cancellation rows -- and a real eInstant
 * TT_CANCEL may arrive with an empty batchContext. Rejecting it would leave its original
 * silently live.
 * A zero-amount transaction ({@link ProjectionTransaction#isZeroAmount}) is exempt too: it is stored
 * but never shown, never opens, anchors or labels a session and adds nothing to its sums (plan D6),
 * so none of the rules' invariants can be broken by one.
 */
@Component
public class IngestRules {

    private static final String SESSION_CURRENCY = """
            select "currency" from "wqPrjSession"
            where "ownerType" = ? and "userId" = ? and "batchId" = ?
            """;

    private final JdbcClient jdbc;
    private final EInstantProperties eInstant;

    public IngestRules(JdbcClient jdbc, EInstantProperties eInstant) {
        this.jdbc = jdbc;
        this.eInstant = eInstant;
    }

    /**
     * Reads the session as the writer's transaction sees it, so a session created earlier in the
     * same batch counts.
     *
     * @return the rejection reason, or null if the transaction may be projected
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String rejection(ProjectionTransaction transaction) {
        if (transaction.isCancellation() || transaction.isZeroAmount()) {
            return null; // exempt from every rule, see the class comment
        }
        if (transaction.isSessioned()
                && !transaction.source().views().containsAll(EnumSet.allOf(ViewKind.class))) {
            // A session's rows serve both endpoints (one visibility flag, D5): only a source that
            // feeds every view may carry a batch. RollupUpdater asserts the same.
            return RejectedTransactionDeadLetters.SESSIONED_SOURCE_NOT_ON_EVERY_VIEW;
        }
        if (!transaction.isSessioned()) {
            return eInstant.isEInstant(transaction.productType()) ? RejectedTransactionDeadLetters.EINSTANT_UNSESSIONED : null;
        }
        String sessionCurrency = jdbc.sql(SESSION_CURRENCY)
                .params(transaction.ownerType(), transaction.userId(), transaction.batchId())
                .query(String.class)
                .optional()
                .orElse(null);
        if (sessionCurrency == null) {
            return null; // this transaction opens the session and sets its currency
        }
        return Objects.equals(sessionCurrency.stripTrailing(), transaction.currency())
                ? null
                : RejectedTransactionDeadLetters.CURRENCY_MISMATCH;
    }
}
