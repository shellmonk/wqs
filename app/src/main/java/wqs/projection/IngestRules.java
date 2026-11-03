package wqs.projection;

import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import wqs.domain.ProjectionTransaction;

/**
 * The per-transaction ingest rules that reject a transaction outright (spec 4.1). A rejected transaction is
 * dead-lettered by the writer and nothing of it is written -- not even its locator row -- while
 * the rest of its batch applies.
 * <ul>
 *   <li>{@code einstant-unsessioned} (plan D7, spec 7.4): a transaction whose product type is eInstant
 *       ({@link EInstantProperties}) and that has no batchId. The ingest half of P-7.</li>
 *   <li>{@code currency-mismatch} (plan D6, O-4): a sessioned transaction whose currency differs from its
 *       existing session's. A session is single-currency, so its sums mean something.</li>
 * </ul>
 * A cancellation is exempt from BOTH rules. It never rolls up and never gets a page-index row,
 * so it can neither mix a session's currency nor put anything into the eInstant view's
 * unsessioned set -- P-7 constrains the view's non-cancellation rows -- and a real eInstant
 * TT_CANCEL may arrive with an empty batchContext. Rejecting it would leave its original
 * silently live.
 * Zero-amount transactions are decided before these rules: they are written to no view or rollup, so
 * neither rule's invariant can be broken by one.
 */
@Component
public class IngestRules {

    private static final String SESSION_CURRENCY = """
            select "currency" from "wqPrjSession"
            where "userId" = ? and "batchId" = ?
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
        if (transaction.isCancellation()) {
            return null; // exempt from both rules, see the class comment
        }
        if (!transaction.isSessioned()) {
            return eInstant.isEInstant(transaction.productType()) ? RejectedTransactionDeadLetters.EINSTANT_UNSESSIONED : null;
        }
        String sessionCurrency = jdbc.sql(SESSION_CURRENCY)
                .params(transaction.userId(), transaction.batchId())
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
