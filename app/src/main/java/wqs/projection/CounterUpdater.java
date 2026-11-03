package wqs.projection;

import static wqs.domain.CounterSentinels.ALL_PRODUCT_TYPES;
import static wqs.domain.CounterSentinels.ALL_TYPES;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import wqs.domain.CounterSentinels;
import wqs.domain.PageUnit;
import wqs.domain.ProjectionTransaction;
import wqs.domain.ViewKind;

/**
 * Maintains the pagination counters (spec 5.7, plan D3): one "wqPrjCount" row per UTC day and
 * key. A multi-year total sums the day rows of its range in one index range.
 *
 * Keys are (productType, type) per day. productType = '' is the ALL-product-types
 * sentinel and type = '' the ALL-transaction-types sentinel ({@link CounterSentinels}), so the
 * common unfiltered query reads one row rather than summing across values.
 *
 * TRANSACTION counters fan out to four keys per day (plan D4): (pt,t), (pt,ALL), (ALL,t), (ALL,ALL),
 * where t is the transaction's type. A transaction with no product type writes only the two ALL-product keys.
 * HIGH_LEVEL counters never carry a type: (pt,ALL) and (ALL,ALL), or (ALL,ALL) alone without a
 * product type.
 *
 * TRANSACTION counters are per transaction, in each view the transaction's source feeds. HIGH_LEVEL counters mirror
 * the page index per view, so they are driven per view from {@link HighLevelResult}:
 * {@link #countHighLevel} for each view in {@code inserted()} at the current key, and
 * {@link #moveHighLevel} for each view in {@code moved()} from
 * (movedFrom, movedFromProductType) to the current key, with that row's visibility.
 *
 * An excl-only change ({@link #uncountExclCancelled}, {@link #recountExclCancelled}) never
 * creates or deletes a row. A decrement that empties a row (cntAll reaches 0) deletes it, keeping rows sparse and stored
 * state independent of the path a moved page unit took.
 *
 * Decrements are strict: every key a decrement targets was counted first (the gate and the
 * per-view page-index mirror guarantee it), so a decrement that does not affect exactly one row
 * is a bug and throws {@link IllegalStateException} naming the full key. The table CHECK
 * constraints (non-negative, excl <= all) reject a decrement that would go below zero. Either
 * failure aborts the projection transaction, which the consumer turns into per-transaction fallback
 * and dead-letter: loud and replayable instead of silent counter drift (spec P-2).
 *
 * All mutators require a surrounding transaction (callers supply it).
 */
@Component
public class CounterUpdater {

    private static final String TABLE = "wqPrjCount";

    private final JdbcClient jdbc;

    public CounterUpdater(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Counts one level-0 row in each view this transaction's source feeds. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void countTransaction(ProjectionTransaction transaction) {
        for (ViewKind view : transaction.source().views()) {
            apply(keys(PageUnit.TRANSACTION, transaction.productType(), transaction.type(), transaction.occurredAt()), transaction.userId(),
                    view, PageUnit.TRANSACTION, +1, true);
        }
    }

    /**
     * Counts one high-level page unit in ONE view: +1 cntAll and +1 cntExclCancelled (a newly
     * inserted page-index row is visible), at the day of {@code at}, for the row's own
     * product type and the sentinel.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void countHighLevel(long userId, ViewKind view, String productType, Instant at) {
        apply(keys(PageUnit.HIGH_LEVEL, productType, ALL_TYPES, at), userId, view, PageUnit.HIGH_LEVEL,
                +1, true);
    }

    /**
     * Moves one high-level page unit between counter keys in ONE view: -1 cntAll (and -1
     * cntExclCancelled iff {@code visibleExclCancelled}) at keys only the old position has, +1
     * at keys only the new position has. Keys shared by both (same day / product type, and
     * the sentinel on a product-only change) are left untouched, so a move that maps to the same
     * key set is a no-op.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void moveHighLevel(
            long userId, ViewKind view,
            String fromProductType, Instant from,
            String toProductType, Instant to,
            boolean visibleExclCancelled) {
        Set<Key> oldKeys = keys(PageUnit.HIGH_LEVEL, fromProductType, ALL_TYPES, from);
        Set<Key> newKeys = keys(PageUnit.HIGH_LEVEL, toProductType, ALL_TYPES, to);
        Set<Key> removed = new LinkedHashSet<>(oldKeys);
        removed.removeAll(newKeys);
        Set<Key> added = new LinkedHashSet<>(newKeys);
        added.removeAll(oldKeys);
        apply(removed, userId, view, PageUnit.HIGH_LEVEL, -1, visibleExclCancelled);
        apply(added, userId, view, PageUnit.HIGH_LEVEL, +1, visibleExclCancelled);
    }

    /**
     * Removes one row from the excl-cancelled tally, leaving cntAll untouched: a cancelled transaction
     * still exists and is still returned when the filter is off.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void uncountExclCancelled(
            long userId, ViewKind view, PageUnit unit, String productType, String type,
            Instant at) {
        for (Key key : keys(unit, productType, type, at)) {
            decrementExcl(userId, view, unit, key);
        }
    }

    /**
     * Returns one row to the excl-cancelled tally, leaving cntAll untouched: the inverse of
     * {@link #uncountExclCancelled}, for a page-index row revived by a live transaction joining a
     * session whose row in that view had been hidden by cancels. Strict like every other
     * decrement-side operation: the row must exist (it was counted when the page unit was
     * inserted), and the CHECK rejects excl &gt; all.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recountExclCancelled(
            long userId, ViewKind view, PageUnit unit, String productType, String type,
            Instant at) {
        for (Key key : keys(unit, productType, type, at)) {
            incrementExcl(userId, view, unit, key);
        }
    }

    // --- internals -----------------------------------------------------------------------

    /** One counter row address. */
    private record Key(String productType, String type, LocalDate day) {}

    /**
     * The key set of one page unit: {own product type, ALL} x {own type, ALL} for TRANSACTION,
     * {own product type, ALL} x {ALL} for HIGH_LEVEL (its {@code type} is ignored), at the UTC day of {@code at}.
     */
    private static Set<Key> keys(PageUnit unit, String productType, String type, Instant at) {
        LocalDate day = LocalDate.ofInstant(at, ZoneOffset.UTC);
        Set<String> products = new LinkedHashSet<>();
        if (productType != null) {
            products.add(productType);
        }
        products.add(ALL_PRODUCT_TYPES);
        Set<String> types = new LinkedHashSet<>();
        if (unit == PageUnit.TRANSACTION) {
            types.add(type);
        }
        types.add(ALL_TYPES);
        Set<Key> keys = new LinkedHashSet<>();
        for (String p : products) {
            for (String t : types) {
                keys.add(new Key(p, t, day));
            }
        }
        return keys;
    }

    private void apply(
            Set<Key> keys, long userId, ViewKind view, PageUnit unit, int delta, boolean excl) {
        for (Key key : keys) {
            if (delta > 0) {
                increment(userId, view, unit, key, excl);
            } else {
                decrement(userId, view, unit, key, excl);
            }
        }
    }

    /** The row key every statement below addresses, appended after its SET values. */
    private static final String ROW_IS = """
            where "userId" = ? and "viewKind" = ? and "pageUnit" = ? and "productType" = ?
              and "type" = ? and "day" = ?
            """;

    // The table name comes from a fixed constant only; every value is bound.
    private static final String INCREMENT = """
            insert into "wqPrjCount" ("userId", "viewKind", "pageUnit", "productType", "type", "day",
                    "cntAll", "cntExclCancelled")
            values (?, ?, ?, ?, ?, ?, ?, ?)
            on conflict ("userId", "viewKind", "pageUnit", "productType", "type", "day")
            do update set "cntAll" = "wqPrjCount"."cntAll" + ?,
                          "cntExclCancelled" = "wqPrjCount"."cntExclCancelled" + ?
            """;
    private static final String DECREMENT =
            "update \"wqPrjCount\" set \"cntAll\" = \"wqPrjCount\".\"cntAll\" - ?\n" + ROW_IS
                    + "returning \"cntAll\"";
    private static final String DECREMENT_WITH_EXCL =
            "update \"wqPrjCount\" set \"cntAll\" = \"wqPrjCount\".\"cntAll\" - ?,"
                    + " \"cntExclCancelled\" = \"wqPrjCount\".\"cntExclCancelled\" - ?\n" + ROW_IS
                    + "returning \"cntAll\"";
    private static final String DELETE = "delete from \"wqPrjCount\"\n" + ROW_IS;
    private static final String DECREMENT_EXCL =
            "update \"wqPrjCount\" set \"cntExclCancelled\" = \"wqPrjCount\".\"cntExclCancelled\" - ?\n"
                    + ROW_IS;
    private static final String INCREMENT_EXCL =
            "update \"wqPrjCount\" set \"cntExclCancelled\" = \"wqPrjCount\".\"cntExclCancelled\" + ?\n"
                    + ROW_IS;

    private void increment(long userId, ViewKind view, PageUnit unit, Key key, boolean excl) {
        long exclDelta = excl ? 1L : 0L;
        jdbc.sql(INCREMENT)
                .params(userId, (short) view.code(), unit.code(), key.productType(), key.type(),
                        key.day(), 1L, exclDelta, 1L, exclDelta)
                .update();
    }

    /**
     * Decrements cntAll (and cntExclCancelled iff {@code excl}); a row left at cntAll = 0 is
     * deleted. Rows are sparse (spec 5.7) and must stay so: a move would otherwise leave an empty
     * row at every key a page unit passed through, making stored state depend on arrival order
     * (spec P-1). cntExclCancelled &lt;= cntAll (CHECK), so a deleted row holds nothing.
     */
    private void decrement(long userId, ViewKind view, PageUnit unit, Key key, boolean excl) {
        var statement = excl
                ? jdbc.sql(DECREMENT_WITH_EXCL).params(1L, 1L)
                : jdbc.sql(DECREMENT).param(1L);
        Long remaining = statement
                .params(userId, (short) view.code(), unit.code(), key.productType(), key.type(),
                        key.day())
                .query(Long.class)
                .optional()
                .orElse(null);
        requireOneRow(remaining == null ? 0 : 1, userId, view, unit, key);
        if (remaining == 0L) {
            jdbc.sql(DELETE)
                    .params(rowIs(userId, view, unit, key))
                    .update();
        }
    }

    private void decrementExcl(long userId, ViewKind view, PageUnit unit, Key key) {
        int rows = jdbc.sql(DECREMENT_EXCL)
                .param(1L)
                .params(rowIs(userId, view, unit, key))
                .update();
        requireOneRow(rows, userId, view, unit, key);
    }

    private void incrementExcl(long userId, ViewKind view, PageUnit unit, Key key) {
        int rows = jdbc.sql(INCREMENT_EXCL)
                .param(1L)
                .params(rowIs(userId, view, unit, key))
                .update();
        requireOneRow(rows, userId, view, unit, key);
    }

    private static void requireOneRow(
            int rows, long userId, ViewKind view, PageUnit unit, Key key) {
        if (rows != 1) {
            throw new IllegalStateException(
                    "counter update hit " + rows + " rows, expected 1: table="
                            + TABLE + " userId=" + userId + " view=" + view
                            + " unit=" + unit + " productType='" + key.productType() + "'"
                            + " type='" + key.type() + "'"
                            + " day=" + key.day());
        }
    }

    /** The bind values of {@link #ROW_IS}, in order. */
    private static Object[] rowIs(long userId, ViewKind view, PageUnit unit, Key key) {
        return new Object[] {
                userId, (short) view.code(), unit.code(), key.productType(), key.type(), key.day()};
    }
}
