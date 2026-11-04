package wqs.read;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import wqs.domain.ProjectionTransaction;
import wqs.domain.SourceKind;

/**
 * What one endpoint shows of the stored data (plan D1). Ingestion stores everything -- every
 * source, every owner type, zero-amount stream transactions -- and every read query binds its
 * endpoint's policy: {@code "ownerType" = ?} and, unless {@code includeZeroAmounts}, {@code not
 * "zeroAmount"} (and {@code "zeroAmount" = false} on the counters).
 *
 * <p>{@code sources} is the endpoint's source set. While the per-endpoint SQL views and the counters'
 * view kind still exist they select the sources; the policy carries the set so that it is the one
 * place stating what an endpoint shows, and so that the views can later give way to a bound
 * {@code "source" = any(?)}.
 *
 * <p>Both endpoints show players only and never zero-amount rows (spec 4.1).
 */
public record ReadPolicy(Set<SourceKind> sources, short ownerType, boolean includeZeroAmounts) {

    /** /web: transactions and claimable prizes of players, no zero-amount rows. */
    public static final ReadPolicy WEB = new ReadPolicy(
            EnumSet.of(SourceKind.TRANSACTION, SourceKind.CLAIMABLE_PRIZE), ProjectionTransaction.PLAYER, false);

    /** /cc: every source of players, no zero-amount rows. */
    public static final ReadPolicy CC = new ReadPolicy(
            EnumSet.allOf(SourceKind.class), ProjectionTransaction.PLAYER, false);

    public ReadPolicy {
        Objects.requireNonNull(sources, "sources");
        if (sources.isEmpty()) {
            throw new IllegalArgumentException("a read policy shows at least one source");
        }
        sources = Set.copyOf(sources);
    }

    /**
     * The zero-amount predicate for a transaction relation qualified as {@code relation}: a fixed
     * fragment chosen here, never a value -- empty when zero-amount rows are shown.
     */
    public String zeroAmountFilter(String relation) {
        return includeZeroAmounts ? "" : " and not (" + relation + ".\"zeroAmount\")";
    }
}
