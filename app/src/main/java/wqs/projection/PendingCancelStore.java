package wqs.projection;

import static wqs.projection.Binds.numeric;
import static wqs.projection.Binds.text;
import static wqs.projection.Binds.timestamptz;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import wqs.domain.ProjectionTransaction;
import wqs.domain.SourceKind;

/**
 * Parks cancels whose target has not arrived, and hands them back when it does (spec 6.3).
 *
 * A cancel targets a transaction of its own source and its own user, so a parked cancel waits for
 * (cancel.userId, cancel.source, cancel.cancels) -- never for the sourceId alone, which different
 * sources may share. A cancel naming another user's transaction therefore never drains: it stays parked,
 * visible through the oldest-age gauge, rather than failing the batch that delivers the original.
 * Every field of the cancel is stored, so a drained cancel is the cancel that arrived.
 *
 * {@link #park} and {@link #drainFor} run inside the projection transaction (the writer supplies
 * it); the gauges' {@link #depth} and {@link #oldestParkedAt} read committed state.
 */
@Component
public class PendingCancelStore {

    private static final String PARK = """
            insert into "wqPrjPendingCancel" ("source", "cancelSourceId", "cancelsSourceId", "userId",
                    "occurredAt", "productId", "productType", "batchId", "subBatchId", "type", "amount",
                    "currency", "balanceAfter", "detail")
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict do nothing
            """;
    /** The targets arrive as three parallel arrays, so the statement is one whatever their number. */
    private static final String DRAIN = """
            delete from "wqPrjPendingCancel"
            where ("source", "cancelsSourceId", "userId") in (
                select * from unnest(cast(? as smallint[]), cast(? as text[]), cast(? as bigint[])))
            returning "source", "cancelSourceId", "cancelsSourceId", "userId", "occurredAt",
                      "productId", "productType", "batchId", "subBatchId", "type", "amount",
                      "currency", "balanceAfter", "detail"
            """;
    private static final String DEPTH = """
            select count(*) from "wqPrjPendingCancel"
            """;
    private static final String OLDEST = """
            select min("parkedAt") from "wqPrjPendingCancel"
            """;

    /** A parked row as the cancel that arrived. */
    private static final RowMapper<ProjectionTransaction> CANCEL = (rs, n) -> new ProjectionTransaction(
            rs.getLong("userId"),
            SourceKind.ofCode(rs.getShort("source")),
            rs.getString("cancelSourceId"),
            rs.getObject("occurredAt", OffsetDateTime.class).toInstant(),
            rs.getString("productId"),
            rs.getString("productType"),
            rs.getString("batchId"),
            rs.getString("subBatchId"),
            rs.getString("type"),
            rs.getBigDecimal("amount"),
            rs.getString("currency"),
            rs.getBigDecimal("balanceAfter"),
            rs.getString("cancelsSourceId"),
            rs.getString("detail"));

    private final JdbcClient jdbc;

    public PendingCancelStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Parks a cancel; parking the same cancel again is a no-op. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void park(ProjectionTransaction cancel) {
        if (!cancel.isCancellation()) {
            throw new IllegalArgumentException("not a cancellation: " + cancel.sourceId());
        }
        jdbc.sql(PARK)
                .params((short) cancel.source().code(), cancel.sourceId(), cancel.cancels(),
                        cancel.userId(), timestamptz(cancel.occurredAt()), text(cancel.productId()),
                        text(cancel.productType()), text(cancel.batchId()), text(cancel.subBatchId()),
                        text(cancel.type()), cancel.amount(), text(cancel.currency()),
                        numeric(cancel.balanceAfter()), text(cancel.detailJson()))
                .update();
    }

    /**
     * Removes and returns the parked cancels targeting any of {@code targets}, ordered by
     * (source, cancel sourceId) so the order they are applied in does not depend on storage.
     * A cancel matches a target only when source, cancelled sourceId AND userId all agree.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<ProjectionTransaction> drainFor(Set<TransactionRef> targets) {
        if (targets.isEmpty()) {
            return List.of();
        }
        List<TransactionRef> keys = List.copyOf(targets);
        short[] sources = new short[keys.size()];
        String[] sourceIds = new String[keys.size()];
        long[] userIds = new long[keys.size()];
        for (int i = 0; i < keys.size(); i++) {
            sources[i] = (short) keys.get(i).source().code();
            sourceIds[i] = keys.get(i).sourceId();
            userIds[i] = keys.get(i).userId();
        }
        return jdbc.sql(DRAIN)
                .params(sources, sourceIds, userIds)
                .query(CANCEL)
                .list()
                .stream()
                .sorted(Comparator.comparing((ProjectionTransaction c) -> (short) c.source().code())
                        .thenComparing(ProjectionTransaction::sourceId))
                .toList();
    }

    public int depth() {
        return jdbc.sql(DEPTH).query(Integer.class).single();
    }

    public Optional<Instant> oldestParkedAt() {
        return jdbc.sql(OLDEST)
                .query((rs, n) -> rs.getObject(1, OffsetDateTime.class))
                .optional()
                .map(OffsetDateTime::toInstant);
    }
}
