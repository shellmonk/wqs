package wqs.read;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import wqs.domain.ProjectionTransaction;
import wqs.domain.SourceKind;

/**
 * What one endpoint shows of the stored data (plan D1). Ingestion stores everything -- every
 * source, every owner type, zero-amount stream transactions -- and nothing stored knows an endpoint:
 * every read query binds its endpoint's policy instead, through {@link #bind}:
 * <ul>
 *   <li>{@code "source" = any (cast(:sources as smallint[]))} -- a session by its "firstSource";</li>
 *   <li>{@code "ownerType" = :ownerType};</li>
 *   <li>{@code (cast(:includeZeroAmounts as boolean) or not "zeroAmount")}.</li>
 * </ul>
 * The predicates are written literally in each statement (ReadPolicyGuardTest checks it); only the
 * values come from here.
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

    /** The policy of an endpoint: what every query class of that endpoint must hold. */
    public static ReadPolicy of(ViewKind view) {
        return switch (view) {
            case WEB -> WEB;
            case CC -> CC;
        };
    }

    /**
     * Fails unless {@code query} holds its endpoint's policy (final-review M2): a /web class wired with
     * the /cc policy, or the reverse, fails at its first use instead of serving the other endpoint's data.
     */
    public static void requireOwn(ViewKind view, ReadPolicy held, Object query) {
        if (!of(view).equals(held)) {
            throw new IllegalStateException(query.getClass().getName() + " serves " + view
                    + " but holds another endpoint's read policy");
        }
    }

    public ReadPolicy {
        Objects.requireNonNull(sources, "sources");
        if (sources.isEmpty()) {
            throw new IllegalArgumentException("a read policy shows at least one source");
        }
        sources = Set.copyOf(sources);
    }

    /** The source codes shown, ascending: the {@code :sources} array bind. */
    public short[] sourceCodes() {
        int[] codes = sources.stream().mapToInt(SourceKind::code).sorted().toArray();
        short[] out = new short[codes.length];
        for (int i = 0; i < codes.length; i++) {
            out[i] = (short) codes[i];
        }
        return out;
    }

    /** Binds {@code :sources}, {@code :ownerType} and {@code :includeZeroAmounts}. */
    public JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec) {
        return spec.param("sources", sourceCodes())
                .param("ownerType", ownerType)
                .param("includeZeroAmounts", includeZeroAmounts);
    }
}
