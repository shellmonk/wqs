package wqs.dto;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * What one endpoint may show. Visibility is decided when reading, never when storing: every row is
 * stored, and each query binds the policy of the endpoint that asked.
 *
 * <p>Sessions are listed by their stored totals. That is safe because only sources every policy
 * shows can carry a batch ({@link Source#carriesBatch()}, enforced at ingest). A policy that hides
 * TRANSACTION or CLAIMABLE_PRIZE would need session totals computed from its visible rows.
 */
public record ReadPolicy(Set<Source> sources) {

    /** Players' own view: transactions and claimable prizes. */
    public static final ReadPolicy WEB = new ReadPolicy(EnumSet.of(Source.TRANSACTION, Source.CLAIMABLE_PRIZE));

    /** Customer care: every source. */
    public static final ReadPolicy CC = new ReadPolicy(EnumSet.allOf(Source.class));

    public ReadPolicy {
        sources = Set.copyOf(sources);
    }

    /** The sources' stored codes, for JPA queries. */
    public List<Short> sourceCodes() {
        return sources.stream().map(Source::code).sorted().toList();
    }

    /** The sources' stored codes as an array, for native queries ({@code = any(cast(:sources as int[]))}). */
    public Integer[] sourceCodeArray() {
        return sourceCodes().stream().map(Short::intValue).toArray(Integer[]::new);
    }
}
