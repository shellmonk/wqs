package wqs.dto;

import java.util.EnumSet;
import java.util.Set;

/**
 * What one endpoint may show. Visibility is decided when reading, never when storing: every row is
 * stored, and each query binds the policy of the endpoint that asked.
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
