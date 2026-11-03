package wqs.projection;

import static wqs.projection.Binds.text;
import static wqs.projection.Binds.timestamptz;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import wqs.domain.ProjectionTransaction;
import wqs.domain.SourceKind;
import wqs.domain.ViewKind;

/**
 * Maintains the level-1/2 page index, "wqPrjTimeline" (spec 5.6): exactly one row per session and
 * one per unsessioned transaction, shared by both endpoints. A row is in view CC always and in view
 * WEB when "onWeb" -- an unsessioned transaction of a WEB source, or a session that some WEB-source
 * transaction has joined. Per-view effects below are effects on that row's per-view columns.
 *
 * A session row is keyed and populated from the SESSION's current key in {@link RollupResult}
 * (its start and product), never from the triggering projected transaction: a session's start moves earlier when
 * an earlier projected transaction arrives, so the row is MOVED (the start is in the primary key and the partition
 * key) and its productId / productType updated in place when only the product changed. A transaction joining a
 * session with an unchanged key inserts nothing in views that already hold the row, which keeps
 * page counts equal to the number of high-level rows rather than the number of transactions.
 * Every sessioned projected transaction also ensures the row is in each view its own source feeds
 * (a WEB-source transaction joining a CC-only session sets "onWeb"), so the end state does not
 * depend on which source opened the session.
 *
 * Visibility is per view -- "visibleExclCancelled" for CC, "webVisibleExclCancelled" for WEB: a
 * session row in view V is visible while the
 * session has at least one surviving (non-cancelled, non-cancellation) transaction whose source feeds V.
 * {@link CancellationApplier} hides a row when a cancel removes the last such transaction; here, a live
 * transaction joining a session whose row in a view its source feeds is hidden REVIVES it, since the
 * transaction is itself a surviving transaction in that view.
 *
 * Currency: a session row carries the SESSION's currency (from {@link RollupResult#sessionCurrency},
 * exactly as "wqPrjSession" holds it). A session is single-currency -- a transaction of another currency
 * is dead-lettered before it gets here (plan D6) -- so the currency never changes and rows never
 * need re-syncing. An unsessioned row carries its transaction's own currency.
 *
 * A cancellation never creates a row; its effects are applied by {@link CancellationApplier}.
 */
@Component
public class HighLevelUpdater {

    private static final short KIND_SESSION = 0;

    private final JdbcClient jdbc;

    public HighLevelUpdater(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Applies one projected transaction's effect on the page index. A move is a delete plus an insert, so callers
     * must supply the transaction.
     *
     * @param rollup what {@link RollupUpdater} did for this projected transaction ({@link RollupResult#NONE} for
     *               unsessioned transactions and cancellations)
     * @return the views where a row was inserted, where an existing session row moved or
     *         changed product, and where a hidden session row was revived; see
     *         {@link HighLevelResult}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public HighLevelResult applyTransaction(ProjectionTransaction transaction, RollupResult rollup) {
        if (transaction.isCancellation()) {
            return HighLevelResult.NONE;
        }
        if (!transaction.isSessioned()) {
            boolean inserted = insert(transaction.userId(), transaction.occurredAt(),
                    (short) transaction.source().code(), transaction.sourceId(), transaction.productId(),
                    transaction.productType(), transaction.currency(), Flags.newFor(transaction.source()));
            return new HighLevelResult(inserted ? transaction.source().views() : Set.of(),
                    Set.of(), null, null, Map.of(), Set.of());
        }
        if (rollup.sessionStart() == null) {
            throw new IllegalStateException(
                    "sessioned transaction " + transaction.sourceId() + " has no session key in its RollupResult");
        }
        Map<ViewKind, Boolean> movedVisible = HighLevelResult.newViewMap();
        boolean startMoved = rollup.sessionKeyChanged()
                && !rollup.sessionStart().equals(rollup.previousSessionStart());
        if (rollup.sessionKeyChanged()) {
            Flags flags = startMoved ? move(transaction, rollup) : updateProduct(transaction, rollup);
            if (flags != null) {
                movedVisible.put(ViewKind.CC, flags.ccVisible());
                if (flags.onWeb()) {
                    movedVisible.put(ViewKind.WEB, flags.webVisible());
                }
            }
        }
        Set<ViewKind> moved = movedVisible.keySet();
        // Materialise the session row in every view THIS transaction's source feeds, whatever order the
        // session's transactions arrived in (P-1). The transaction is live (cancellations return above), so
        // the session has a surviving transaction in that view and the row is visible there.
        Set<ViewKind> inserted = HighLevelResult.newViewSet();
        Set<ViewKind> revived = HighLevelResult.newViewSet();
        if (insert(transaction.userId(), rollup.sessionStart(), KIND_SESSION, transaction.batchId(),
                rollup.sessionProductId(), rollup.sessionProductType(), rollup.sessionCurrency(),
                Flags.newFor(transaction.source()))) {
            inserted.addAll(transaction.source().views());
        } else {
            // The row already existed: it may lack this source's views (a CC-only session that a
            // WEB-source transaction now joins) or have been hidden by cancels in them.
            Flags before = lock(transaction, rollup);
            boolean feedsWeb = transaction.source().views().contains(ViewKind.WEB);
            if (!before.ccVisible()) {
                revived.add(ViewKind.CC);
            }
            if (feedsWeb && !before.onWeb()) {
                inserted.add(ViewKind.WEB);
            } else if (feedsWeb && !before.webVisible()) {
                revived.add(ViewKind.WEB);
            }
            if (!inserted.isEmpty() || !revived.isEmpty()) {
                jdbc.sql(SHOW)
                        .params(feedsWeb, feedsWeb, transaction.userId(), timestamptz(rollup.sessionStart()),
                                KIND_SESSION, transaction.batchId())
                        .update();
            }
        }
        return new HighLevelResult(inserted, moved,
                moved.isEmpty() ? null : rollup.previousSessionStart(),
                moved.isEmpty() ? null : rollup.previousSessionProductType(),
                movedVisible, revived);
    }

    /** The existing session row's flags, locked for the update that follows. */
    private Flags lock(ProjectionTransaction transaction, RollupResult rollup) {
        return jdbc.sql(LOCK)
                .params(transaction.userId(), timestamptz(rollup.sessionStart()), KIND_SESSION, transaction.batchId())
                .query(FLAGS)
                .single();
    }

    /** True iff the row was inserted (false: a row with this key already exists). */
    private boolean insert(long userId, Instant at, short kind, String refId,
                           String productId, String productType, String currency, Flags flags) {
        return jdbc.sql(INSERT)
                .params(userId, timestamptz(at), kind, refId, text(productId), text(productType),
                        text(currency), flags.onWeb(), flags.ccVisible(), flags.webVisible())
                .update() > 0;
    }

    /**
     * Moves the session row to its new key, keeping its flags.
     *
     * @return the moved row's flags, or null when there was no row
     */
    private Flags move(ProjectionTransaction transaction, RollupResult rollup) {
        Flags flags = jdbc.sql(DELETE)
                .params(transaction.userId(), timestamptz(rollup.previousSessionStart()), KIND_SESSION,
                        transaction.batchId())
                .query(FLAGS)
                .optional()
                .orElse(null);
        if (flags == null) {
            return null;
        }
        insert(transaction.userId(), rollup.sessionStart(), KIND_SESSION, transaction.batchId(),
                rollup.sessionProductId(), rollup.sessionProductType(), rollup.sessionCurrency(), flags);
        return flags;
    }

    /**
     * Updates productId and productType in place.
     *
     * @return the row's flags, or null when there was no row
     */
    private Flags updateProduct(ProjectionTransaction transaction, RollupResult rollup) {
        return jdbc.sql(UPDATE_PRODUCT)
                .params(text(rollup.sessionProductId()), text(rollup.sessionProductType()),
                        transaction.userId(), timestamptz(rollup.sessionStart()), KIND_SESSION, transaction.batchId())
                .query(FLAGS)
                .optional()
                .orElse(null);
    }

    /**
     * A timeline row's per-view columns: "onWeb" (the row is in view WEB), "visibleExclCancelled"
     * (CC's visibility) and "webVisibleExclCancelled" (WEB's; false when not on WEB).
     */
    private record Flags(boolean onWeb, boolean ccVisible, boolean webVisible) {

        /** A new row, created by a live transaction of {@code source}: visible in every view it is in. */
        static Flags newFor(SourceKind source) {
            boolean onWeb = source.views().contains(ViewKind.WEB);
            return new Flags(onWeb, true, onWeb);
        }
    }

    private static final RowMapper<Flags> FLAGS = (rs, n) -> new Flags(
            rs.getBoolean("onWeb"), rs.getBoolean("visibleExclCancelled"),
            rs.getBoolean("webVisibleExclCancelled"));

    private static final String KEY = """
            "userId" = ? and "occurredAt" = ? and "kind" = ? and "refId" = ?""";
    private static final String RETURNING_FLAGS = """
            returning "onWeb", "visibleExclCancelled", "webVisibleExclCancelled"
            """;
    private static final String INSERT = """
            insert into "wqPrjTimeline" ("userId", "occurredAt", "kind", "refId", "productId",
                    "productType", "currency", "onWeb", "visibleExclCancelled", "webVisibleExclCancelled")
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict do nothing
            """;
    private static final String LOCK = """
            select "onWeb", "visibleExclCancelled", "webVisibleExclCancelled" from "wqPrjTimeline"
            where %s
            for update
            """.formatted(KEY);
    // A live transaction makes the row visible on /cc and, when its source feeds WEB, puts it on
    // /web and visible there.
    private static final String SHOW = """
            update "wqPrjTimeline" set "visibleExclCancelled" = true,
                "onWeb" = "onWeb" or ?,
                "webVisibleExclCancelled" = "webVisibleExclCancelled" or ?
            where %s
            """.formatted(KEY);
    private static final String DELETE = """
            delete from "wqPrjTimeline"
            where %s
            """.formatted(KEY) + RETURNING_FLAGS;
    private static final String UPDATE_PRODUCT = """
            update "wqPrjTimeline" set "productId" = ?, "productType" = ?
            where %s
            """.formatted(KEY) + RETURNING_FLAGS;
}
