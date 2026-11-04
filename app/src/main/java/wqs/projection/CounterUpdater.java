package wqs.projection;

import static wqs.domain.CounterSentinels.NO_PRODUCT_TYPE;
import static wqs.domain.CounterSentinels.NO_TYPE;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import wqs.domain.PageUnit;
import wqs.domain.ProjectionTransaction;

/**
 * Maintains the owner-month counts, "wqOwnerMonth" (plan D5): one row per owner, UTC month and EXACT
 * combination (source, unit, productType, type, zeroAmount). There is no "all" row: every counted
 * thing increments exactly one row, so a filtered total is the sum of the rows its filter selects.
 *
 * <ul>
 *   <li>TX: one per transaction, at its own month, source, product type ('' = none), type code and
 *       zero-amount flag -- cancellations included (counters equal live COUNTs, P-2).</li>
 *   <li>ENTRY: one per level-1/2 page entry (plan D4): a session, under its first transaction's source
 *       ("firstSource": a session belongs to a read policy iff that source does) at its current (month
 *       of "startedAt", "productType"); or a standalone transaction (no batch id, not a cancellation),
 *       under its own source, month and product type -- a zero-amount one under "zeroAmount" = true
 *       (plan D6). "type" is always ''.</li>
 * </ul>
 *
 * "cntExclCancelled" counts the visible ones: a transaction not cancelled, a session with a surviving
 * transaction. An excl-only change never creates or deletes a row; a decrement that empties a row
 * ("cnt" = 0) deletes it, so rows stay sparse and stored state does not depend on the path a moved
 * session took (spec P-1).
 *
 * Strict: every key a decrement targets was counted first (the insert gate guarantees it), so a
 * decrement that does not hit exactly one row is a bug and throws {@link IllegalStateException}
 * naming the full key; the table CHECKs reject a negative or excl &gt; cnt. Either aborts the
 * projection transaction -- loud and replayable instead of silent drift (spec P-2).
 *
 * All mutators require a surrounding transaction (callers supply it).
 */
@Component
public class CounterUpdater {

    private final JdbcClient jdbc;

    public CounterUpdater(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Counts one transaction at TX: +1, visible (a new transaction is never cancelled yet). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void countTransaction(ProjectionTransaction transaction) {
        increment(Key.tx(transaction.ownerType(), transaction.userId(), transaction.occurredAt(),
                (short) transaction.source().code(), transaction.productType(), transaction.type(),
                transaction.isZeroAmount()), true);
    }

    /** A transaction was cancelled: -1 from its TX excl-cancelled tally only. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void uncountTransactionExcl(short ownerType, long userId, Instant occurredAt, short source,
                                       String productType, String type, boolean zeroAmount) {
        updateExcl(Key.tx(ownerType, userId, occurredAt, source, productType, type, zeroAmount), -1);
    }

    /**
     * Counts one new, visible ENTRY: a standalone transaction under its source, or a session under its
     * first source.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void countEntry(short ownerType, long userId, Instant at, short source, String productType,
                           boolean zeroAmount) {
        increment(Key.entry(ownerType, userId, at, source, productType, zeroAmount), true);
    }

    /** An entry became hidden (its transaction, or a session's last survivor, was cancelled). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void uncountEntryExcl(short ownerType, long userId, Instant at, short source, String productType,
                                 boolean zeroAmount) {
        updateExcl(Key.entry(ownerType, userId, at, source, productType, zeroAmount), -1);
    }

    /**
     * Moves one session ENTRY from its previous (start, first source, product type, visibility) to its
     * current one: a re-anchor or relabel moves it between rows (only when the month, source or product
     * type differs -- the key holds no finer time), a revive changes only its excl tally.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void moveSession(short ownerType, long userId,
                            Instant fromAt, short fromSource, String fromProductType, boolean fromVisible,
                            Instant toAt, short toSource, String toProductType, boolean toVisible) {
        Key from = Key.entry(ownerType, userId, fromAt, fromSource, fromProductType, false);
        Key to = Key.entry(ownerType, userId, toAt, toSource, toProductType, false);
        if (from.equals(to)) {
            if (fromVisible != toVisible) {
                updateExcl(to, toVisible ? +1 : -1);
            }
            return;
        }
        decrement(from, fromVisible);
        increment(to, toVisible);
    }

    // --- internals -----------------------------------------------------------------------

    /** One "wqOwnerMonth" row address. */
    private record Key(short ownerType, long userId, LocalDate month, short source, PageUnit unit,
                       String productType, String type, boolean zeroAmount) {

        static Key tx(short ownerType, long userId, Instant at, short source, String productType, String type,
                      boolean zeroAmount) {
            return new Key(ownerType, userId, monthOf(at), source, PageUnit.TRANSACTION,
                    productType == null ? NO_PRODUCT_TYPE : productType, type, zeroAmount);
        }

        static Key entry(short ownerType, long userId, Instant at, short kind, String productType,
                         boolean zeroAmount) {
            return new Key(ownerType, userId, monthOf(at), kind, PageUnit.HIGH_LEVEL,
                    productType == null ? NO_PRODUCT_TYPE : productType, NO_TYPE, zeroAmount);
        }

        Object[] binds() {
            return new Object[] {ownerType, userId, month, source, unit.code(), productType, type, zeroAmount};
        }
    }

    private static LocalDate monthOf(Instant at) {
        return YearMonth.from(at.atZone(ZoneOffset.UTC)).atDay(1);
    }

    /** The row key every statement below addresses, appended after its SET values. */
    private static final String ROW_IS = """
            where "ownerType" = ? and "userId" = ? and "month" = ? and "source" = ? and "unit" = ?
              and "productType" = ? and "type" = ? and "zeroAmount" = ?
            """;

    // Every value is bound.
    private static final String INCREMENT = """
            insert into "wqOwnerMonth" ("ownerType", "userId", "month", "source", "unit", "productType", "type",
                    "zeroAmount", "cnt", "cntExclCancelled")
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict ("ownerType", "userId", "month", "source", "unit", "productType", "type", "zeroAmount")
            do update set "cnt" = "wqOwnerMonth"."cnt" + excluded."cnt",
                          "cntExclCancelled" = "wqOwnerMonth"."cntExclCancelled" + excluded."cntExclCancelled"
            """;
    private static final String DECREMENT =
            "update \"wqOwnerMonth\" set \"cnt\" = \"wqOwnerMonth\".\"cnt\" - ?,"
                    + " \"cntExclCancelled\" = \"wqOwnerMonth\".\"cntExclCancelled\" - ?\n" + ROW_IS
                    + "returning \"cnt\"";
    private static final String DELETE = "delete from \"wqOwnerMonth\"\n" + ROW_IS;
    private static final String UPDATE_EXCL =
            "update \"wqOwnerMonth\" set \"cntExclCancelled\" = \"wqOwnerMonth\".\"cntExclCancelled\" + ?\n"
                    + ROW_IS;

    private void increment(Key key, boolean visible) {
        jdbc.sql(INCREMENT)
                .params(key.binds())
                .params(1L, visible ? 1L : 0L)
                .update();
    }

    /**
     * -1 cnt (and -1 excl iff {@code visible}); a row left at cnt = 0 is deleted. excl &lt;= cnt
     * (CHECK), so a deleted row holds nothing.
     */
    private void decrement(Key key, boolean visible) {
        Long remaining = jdbc.sql(DECREMENT)
                .params(1L, visible ? 1L : 0L)
                .params(key.binds())
                .query(Long.class)
                .optional()
                .orElse(null);
        requireOneRow(remaining == null ? 0 : 1, key);
        if (remaining == 0L) {
            jdbc.sql(DELETE).params(key.binds()).update();
        }
    }

    /** Adds {@code delta} to cntExclCancelled only; the row must exist. */
    private void updateExcl(Key key, long delta) {
        int rows = jdbc.sql(UPDATE_EXCL)
                .param(delta)
                .params(key.binds())
                .update();
        requireOneRow(rows, key);
    }

    private static void requireOneRow(int rows, Key key) {
        if (rows != 1) {
            throw new IllegalStateException(
                    "owner-month count update hit " + rows + " rows, expected 1: table=wqOwnerMonth"
                            + " ownerType=" + key.ownerType() + " userId=" + key.userId()
                            + " month=" + key.month() + " source=" + key.source() + " unit=" + key.unit()
                            + " productType='" + key.productType() + "'"
                            + " type='" + key.type() + "'"
                            + " zeroAmount=" + key.zeroAmount());
        }
    }
}
