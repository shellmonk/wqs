package wqs.projection;

import static wqs.db.generated.Tables.WQ_GROUP_LOCATOR;
import static wqs.db.generated.Tables.WQ_SESSION;

import java.util.Objects;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import wqs.domain.ProjectionItem;

/**
 * The per-item ingest rules that reject an item outright (spec 4.1). A rejected item is
 * dead-lettered by the writer and nothing of it is written -- not even its locator row -- while
 * the rest of its batch applies.
 * <ul>
 *   <li>{@code einstant-unsessioned} (plan D7, spec 7.4): an item whose product type is eInstant
 *       ({@link EInstantProperties}) and that has no batchId. The ingest half of P-7.</li>
 *   <li>{@code currency-mismatch} (plan D6, O-4): a sessioned item whose currency differs from its
 *       existing session's. A session is single-currency, so its sums mean something.</li>
 * </ul>
 * A cancellation is exempt from BOTH rules. It never rolls up and never gets a page-index row,
 * so it can neither mix a session's currency nor put anything into the eInstant view's
 * unsessioned set -- P-7 constrains the view's non-cancellation rows -- and a real eInstant
 * TT_CANCEL may arrive with an empty batchContext. Rejecting it would leave its original
 * silently live.
 * Zero-amount items are decided before these rules: they are written to no view or rollup, so
 * neither rule's invariant can be broken by one.
 */
@Component
public class IngestRules {

    private static final String SESSION_MARKER = "";

    private final DSLContext dsl;
    private final EInstantProperties eInstant;

    public IngestRules(DSLContext dsl, EInstantProperties eInstant) {
        this.dsl = dsl;
        this.eInstant = eInstant;
    }

    /**
     * Reads the session as the writer's transaction sees it, so a session created earlier in the
     * same batch counts.
     *
     * @return the rejection reason, or null if the item may be projected
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String rejection(ProjectionItem item) {
        if (item.isCancellation()) {
            return null; // exempt from both rules, see the class comment
        }
        if (!item.isSessioned()) {
            return eInstant.isEInstant(item.productType()) ? RejectedItemDeadLetters.EINSTANT_UNSESSIONED : null;
        }
        String sessionCurrency = dsl.select(WQ_SESSION.currency)
                .from(WQ_GROUP_LOCATOR)
                .join(WQ_SESSION).on(
                        WQ_SESSION.userId.eq(WQ_GROUP_LOCATOR.userId),
                        WQ_SESSION.startedAt.eq(WQ_GROUP_LOCATOR.startedAt),
                        WQ_SESSION.batchId.eq(WQ_GROUP_LOCATOR.batchId))
                .where(WQ_GROUP_LOCATOR.userId.eq(item.userId()),
                       WQ_GROUP_LOCATOR.batchId.eq(item.batchId()),
                       WQ_GROUP_LOCATOR.subBatchId.eq(SESSION_MARKER))
                .fetchOne(WQ_SESSION.currency);
        if (sessionCurrency == null) {
            return null; // this item opens the session and sets its currency
        }
        return Objects.equals(sessionCurrency.stripTrailing(), item.currency())
                ? null
                : RejectedItemDeadLetters.CURRENCY_MISMATCH;
    }
}
