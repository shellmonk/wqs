package wqs.dto;

import java.util.EnumSet;
import java.util.Set;

/**
 * What one endpoint may show. Visibility is decided when reading, never when storing: every row is
 * stored, and each query binds the policy of the endpoint that asked.
 *
 * <p>Sessions are listed by their stored totals. That is safe because only sources every policy
 * shows can carry a batch ({@link Source#carriesBatch()}, enforced at ingest). A policy that hides
 * TRANSACTION or CLAIMABLE_PRIZE would need session totals computed from its visible rows.
 */
public record ReadPolicy(Set<Source> sources, short ownerType) {

    /** Players' own view: transactions and claimable prizes. */
    public static final ReadPolicy WEB = new ReadPolicy(EnumSet.of(Source.TRANSACTION, Source.CLAIMABLE_PRIZE), (short) 1);

    /** Customer care: every source. */
    public static final ReadPolicy CC = new ReadPolicy(EnumSet.allOf(Source.class), (short) 1);

    public ReadPolicy {
        sources = Set.copyOf(sources);
    }

    public Integer[] sourceCodes() {
        return sources.stream().map(s -> (int) s.code()).sorted().toArray(Integer[]::new);
    }
}
