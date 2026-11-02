package wqs.projection;

import static wqs.db.generated.Tables.WQ_COUNT_DAY;
import static wqs.db.generated.Tables.WQ_COUNT_MONTH;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.Set;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.TableField;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import wqs.domain.PageUnit;
import wqs.domain.ProjectionItem;
import wqs.domain.ViewKind;

/**
 * Maintains the two-tier pagination counters (spec 5.7): "wqCountMonth" for whole months and
 * "wqCountDay" for the partial months at each end of a range. Periods are UTC.
 *
 * productId = 0 is the all-products sentinel, so the common unfiltered query reads one row
 * rather than summing across products. A counted row therefore touches two product keys per
 * tier: its own product (when it has one) and the sentinel. A row with no product touches the
 * sentinel only.
 *
 * ITEM counters are per item, in each view the item's source feeds. HIGH_LEVEL counters mirror
 * the page index per view, so they are driven per view from {@link HighLevelResult}:
 * {@link #countHighLevel} for each view in {@code inserted()} at the current key, and
 * {@link #moveHighLevel} for each view in {@code moved()} from
 * (movedFrom, movedFromProductId) to the current key, with that row's visibility.
 *
 * An excl-only change ({@link #uncountExclCancelled}, {@link #recountExclCancelled}) never
 * creates or deletes a row. A decrement that empties a row (cntAll reaches 0) deletes it, keeping rows sparse and stored
 * state independent of the path a moved page unit took.
 *
 * Decrements are strict: every key a decrement targets was counted first (the gate and the
 * per-view page-index mirror guarantee it), so a decrement that does not affect exactly one row
 * is a bug and throws {@link IllegalStateException} naming the full key. The table CHECK
 * constraints (non-negative, excl <= all) reject a decrement that would go below zero. Either
 * failure aborts the projection transaction, which the consumer turns into per-item fallback
 * and dead-letter: loud and replayable instead of silent counter drift (spec P-2).
 *
 * All mutators require a surrounding transaction (callers supply it).
 */
@Component
public class CounterUpdater {

    private static final int ALL_PRODUCTS = 0;

    private final DSLContext dsl;

    public CounterUpdater(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** Counts one level-0 row in each view this item's source feeds. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void countItem(ProjectionItem item) {
        for (ViewKind view : item.source().views()) {
            apply(keys(item.productId(), item.occurredAt()), item.userId(), view, PageUnit.ITEM,
                    +1, true);
        }
    }

    /**
     * Counts one high-level page unit in ONE view: +1 cntAll and +1 cntExclCancelled (a newly
     * inserted page-index row is visible), at the day and month of {@code at}, for the row's own
     * product and the sentinel.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void countHighLevel(long userId, ViewKind view, Integer productId, Instant at) {
        apply(keys(productId, at), userId, view, PageUnit.HIGH_LEVEL, +1, true);
    }

    /**
     * Moves one high-level page unit between counter keys in ONE view: -1 cntAll (and -1
     * cntExclCancelled iff {@code visibleExclCancelled}) at keys only the old position has, +1
     * at keys only the new position has. Keys shared by both (same day / month / product, and
     * the sentinel on a product-only change) are left untouched, so a move that maps to the same
     * key set is a no-op.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void moveHighLevel(
            long userId, ViewKind view,
            Integer fromProductId, Instant from,
            Integer toProductId, Instant to,
            boolean visibleExclCancelled) {
        Set<Key> oldKeys = keys(fromProductId, from);
        Set<Key> newKeys = keys(toProductId, to);
        Set<Key> removed = new LinkedHashSet<>(oldKeys);
        removed.removeAll(newKeys);
        Set<Key> added = new LinkedHashSet<>(newKeys);
        added.removeAll(oldKeys);
        apply(removed, userId, view, PageUnit.HIGH_LEVEL, -1, visibleExclCancelled);
        apply(added, userId, view, PageUnit.HIGH_LEVEL, +1, visibleExclCancelled);
    }

    /**
     * Removes one row from the excl-cancelled tally, leaving cntAll untouched: a cancelled item
     * still exists and is still returned when the filter is off.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void uncountExclCancelled(
            long userId, ViewKind view, PageUnit unit, Integer productId, Instant at) {
        for (Key key : keys(productId, at)) {
            decrementExcl(tier(key.month()), userId, view, unit, key);
        }
    }

    /**
     * Returns one row to the excl-cancelled tally, leaving cntAll untouched: the inverse of
     * {@link #uncountExclCancelled}, for a page-index row revived by a live item joining a
     * session whose row in that view had been hidden by cancels. Strict like every other
     * decrement-side operation: the row must exist (it was counted when the page unit was
     * inserted), and the CHECK rejects excl &gt; all.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recountExclCancelled(
            long userId, ViewKind view, PageUnit unit, Integer productId, Instant at) {
        for (Key key : keys(productId, at)) {
            incrementExcl(tier(key.month()), userId, view, unit, key);
        }
    }

    // --- internals -----------------------------------------------------------------------

    /** One counter row address within a tier. */
    private record Key(boolean month, int productId, LocalDate period) {}

    private static Set<Key> keys(Integer productId, Instant at) {
        LocalDate day = LocalDate.ofInstant(at, ZoneOffset.UTC);
        LocalDate monthStart = day.withDayOfMonth(1);
        Set<Key> keys = new LinkedHashSet<>();
        if (productId != null) {
            keys.add(new Key(false, productId, day));
            keys.add(new Key(true, productId, monthStart));
        }
        keys.add(new Key(false, ALL_PRODUCTS, day));
        keys.add(new Key(true, ALL_PRODUCTS, monthStart));
        return keys;
    }

    private void apply(
            Set<Key> keys, long userId, ViewKind view, PageUnit unit, int delta, boolean excl) {
        for (Key key : keys) {
            Tier<?> tier = tier(key.month());
            if (delta > 0) {
                increment(tier, userId, view, unit, key, excl);
            } else {
                decrement(tier, userId, view, unit, key, excl);
            }
        }
    }

    private Tier<?> tier(boolean month) {
        return month
                ? new Tier<>(WQ_COUNT_MONTH, WQ_COUNT_MONTH.userId, WQ_COUNT_MONTH.viewKind,
                        WQ_COUNT_MONTH.pageUnit, WQ_COUNT_MONTH.productId, WQ_COUNT_MONTH.period,
                        WQ_COUNT_MONTH.cntAll, WQ_COUNT_MONTH.cntExclCancelled)
                : new Tier<>(WQ_COUNT_DAY, WQ_COUNT_DAY.userId, WQ_COUNT_DAY.viewKind,
                        WQ_COUNT_DAY.pageUnit, WQ_COUNT_DAY.productId, WQ_COUNT_DAY.period,
                        WQ_COUNT_DAY.cntAll, WQ_COUNT_DAY.cntExclCancelled);
    }

    /** The two counter tables share one column shape; this carries a table's typed columns. */
    private record Tier<R extends Record>(
            Table<R> table,
            TableField<R, Long> userId,
            TableField<R, Short> viewKind,
            TableField<R, Short> pageUnit,
            TableField<R, Integer> productId,
            TableField<R, LocalDate> period,
            TableField<R, Long> cntAll,
            TableField<R, Long> cntExclCancelled) {}

    private <R extends Record> void increment(
            Tier<R> t, long userId, ViewKind view, PageUnit unit, Key key, boolean excl) {
        long exclDelta = excl ? 1L : 0L;
        dsl.insertInto(t.table())
                .set(t.userId(), userId)
                .set(t.viewKind(), (short) view.code())
                .set(t.pageUnit(), unit.code())
                .set(t.productId(), key.productId())
                .set(t.period(), key.period())
                .set(t.cntAll(), 1L)
                .set(t.cntExclCancelled(), exclDelta)
                .onConflict(t.userId(), t.viewKind(), t.pageUnit(), t.productId(), t.period())
                .doUpdate()
                .set(t.cntAll(), t.cntAll().plus(1L))
                .set(t.cntExclCancelled(), t.cntExclCancelled().plus(exclDelta))
                .execute();
    }

    /**
     * Decrements cntAll (and cntExclCancelled iff {@code excl}); a row left at cntAll = 0 is
     * deleted. Rows are sparse (spec 5.7) and must stay so: a move would otherwise leave an empty
     * row at every key a page unit passed through, making stored state depend on arrival order
     * (spec P-1). cntExclCancelled &lt;= cntAll (CHECK), so a deleted row holds nothing.
     */
    private <R extends Record> void decrement(
            Tier<R> t, long userId, ViewKind view, PageUnit unit, Key key, boolean excl) {
        var update = dsl.update(t.table()).set(t.cntAll(), t.cntAll().minus(1L));
        var step = excl
                ? update.set(t.cntExclCancelled(), t.cntExclCancelled().minus(1L))
                : update;
        Long remaining = step.where(rowIs(t, userId, view, unit, key))
                .returning(t.cntAll())
                .fetchOptional(t.cntAll())
                .orElse(null);
        requireOneRow(remaining == null ? 0 : 1, t, userId, view, unit, key);
        if (remaining == 0L) {
            dsl.deleteFrom(t.table())
                    .where(rowIs(t, userId, view, unit, key))
                    .execute();
        }
    }

    private <R extends Record> void decrementExcl(
            Tier<R> t, long userId, ViewKind view, PageUnit unit, Key key) {
        int rows = dsl.update(t.table())
                .set(t.cntExclCancelled(), t.cntExclCancelled().minus(1L))
                .where(rowIs(t, userId, view, unit, key))
                .execute();
        requireOneRow(rows, t, userId, view, unit, key);
    }

    private <R extends Record> void incrementExcl(
            Tier<R> t, long userId, ViewKind view, PageUnit unit, Key key) {
        int rows = dsl.update(t.table())
                .set(t.cntExclCancelled(), t.cntExclCancelled().plus(1L))
                .where(rowIs(t, userId, view, unit, key))
                .execute();
        requireOneRow(rows, t, userId, view, unit, key);
    }

    private static <R extends Record> void requireOneRow(
            int rows, Tier<R> t, long userId, ViewKind view, PageUnit unit, Key key) {
        if (rows != 1) {
            throw new IllegalStateException(
                    "counter update hit " + rows + " rows, expected 1: table="
                            + t.table().getName() + " userId=" + userId + " view=" + view
                            + " unit=" + unit + " productId=" + key.productId()
                            + " period=" + key.period());
        }
    }

    private static <R extends Record> org.jooq.Condition rowIs(
            Tier<R> t, long userId, ViewKind view, PageUnit unit, Key key) {
        return t.userId().eq(userId)
                .and(t.viewKind().eq((short) view.code()))
                .and(t.pageUnit().eq(unit.code()))
                .and(t.productId().eq(key.productId()))
                .and(t.period().eq(key.period()));
    }
}
