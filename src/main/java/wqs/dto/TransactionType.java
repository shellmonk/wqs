package wqs.dto;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The transaction-type codes WQS knows, each with its {@link MoneyDirection}. Rows store the code
 * itself as text; this enum only supplies the direction a session or play total applies.
 *
 * <p>The list is open: an unknown code is stored as it arrived and treated as NEUTRAL, never
 * rejected. Undo and rollback types stay NEUTRAL until decided otherwise.
 */
public enum TransactionType {

    // walAccountTransactions.transactionType (docs/reference/source-schema.sql), in the order listed.
    TT_BUY(MoneyDirection.PLAY),
    BUY(MoneyDirection.PLAY),
    BET(MoneyDirection.PLAY),
    TT_BUY_UNDO(MoneyDirection.NEUTRAL),
    PLAYER_CANCEL_BUY(MoneyDirection.NEUTRAL),
    TT_BUY_ROLLBACK(MoneyDirection.NEUTRAL),
    TT_PRIZE_IN(MoneyDirection.WIN),
    PRIZE(MoneyDirection.WIN),
    TT_PRIZE_OUT(MoneyDirection.NEUTRAL),
    TT_PRIZE_OUT_UNDO(MoneyDirection.NEUTRAL),
    TT_TRANSFER_IN(MoneyDirection.NEUTRAL),
    DEPOSIT(MoneyDirection.NEUTRAL),
    TT_TRANSFER_OUT(MoneyDirection.NEUTRAL),
    WITHDRAW(MoneyDirection.NEUTRAL),
    TT_TRANSFER_OUT_TAX(MoneyDirection.NEUTRAL),
    TT_TRANSFER_OUT_UNDO(MoneyDirection.NEUTRAL),
    TT_TRANSFER_IN_UNDO(MoneyDirection.NEUTRAL),
    TT_PERIODIC_CHARGE(MoneyDirection.PLAY),
    TT_REFUND(MoneyDirection.NEUTRAL),
    REFUND(MoneyDirection.NEUTRAL),
    TT_REFUND_OUT(MoneyDirection.NEUTRAL),
    TT_REFUND_OUT_UNDO(MoneyDirection.NEUTRAL),
    TT_PRIZE_ROLLBACK(MoneyDirection.NEUTRAL),
    PRIZE_ROLLBACK(MoneyDirection.NEUTRAL),
    TT_CLOSING_ACCOUNT(MoneyDirection.NEUTRAL),
    TT_CLOSE_ACCOUNT_BENEFICIARY(MoneyDirection.NEUTRAL),
    TT_EMPTYING_ACCOUNT(MoneyDirection.NEUTRAL),
    TT_BLOCKED_ACCOUNT(MoneyDirection.NEUTRAL),
    TT_BALANCE_BANK(MoneyDirection.NEUTRAL),
    TT_MANUAL_ADJUST(MoneyDirection.NEUTRAL),
    TT_MANUAL_ADJUST_UNDO(MoneyDirection.NEUTRAL),
    ADJUST(MoneyDirection.NEUTRAL),
    TT_MANUAL_ADJUST_NON_WITHDRAWABLE(MoneyDirection.NEUTRAL),
    TT_MANUAL_ADJUST_NON_WITHDRAWABLE_UNDO(MoneyDirection.NEUTRAL),
    TT_COMMISSION(MoneyDirection.NEUTRAL),
    TT_RESERVE(MoneyDirection.NEUTRAL),
    TT_RESERVE_REJECT(MoneyDirection.NEUTRAL),
    TT_RESERVE_CANCEL(MoneyDirection.NEUTRAL),
    RESERVE_FUND_DEPOSIT(MoneyDirection.NEUTRAL),
    RESERVE_FUND_WITHDRAWAL(MoneyDirection.NEUTRAL),
    TT_RETAINER_PAYBACK(MoneyDirection.NEUTRAL),
    TT_WRITE_OFF(MoneyDirection.NEUTRAL),
    TT_SERVICE_COST(MoneyDirection.NEUTRAL),
    TT_TRANSFER_TO_ACCOUNT(MoneyDirection.NEUTRAL),
    TT_TRANSFER_FROM_ACCOUNT(MoneyDirection.NEUTRAL),
    TT_EXTERNAL_WALLET_ADJUSTMENT(MoneyDirection.NEUTRAL),
    TT_TOMBSTONE(MoneyDirection.NEUTRAL),
    TT_CANCEL(MoneyDirection.NEUTRAL),
    CANCEL(MoneyDirection.NEUTRAL),
    TT_FRAUD(MoneyDirection.NEUTRAL),
    TT_PURGE(MoneyDirection.NEUTRAL),
    TT_CHARGEBACK(MoneyDirection.NEUTRAL),
    FINISH(MoneyDirection.NEUTRAL),
    SECURE_FUNDING(MoneyDirection.NEUTRAL),
    PROMOTION(MoneyDirection.NEUTRAL),
    TT_SELF_EXCLUSION(MoneyDirection.NEUTRAL),

    // Codes the delta feed will use: a payment with no paymentType, and every claimable prize.
    PAYMENT(MoneyDirection.NEUTRAL),
    CLAIMABLE_PRIZE(MoneyDirection.NEUTRAL),

    // walPayments.paymentType.
    PT_BUY(MoneyDirection.NEUTRAL),
    PT_BUY_UNDO(MoneyDirection.NEUTRAL),
    PT_RETAILER_BUY(MoneyDirection.NEUTRAL),
    PT_RETAILER_BUY_UNDO(MoneyDirection.NEUTRAL),
    PT_COMMISSION(MoneyDirection.NEUTRAL),
    PT_PRIZE(MoneyDirection.NEUTRAL),
    PT_MEDIUM_PRIZE(MoneyDirection.NEUTRAL),
    PT_HIGH_PRIZE(MoneyDirection.NEUTRAL),
    PT_GRAND_PRIZE(MoneyDirection.NEUTRAL),
    PT_MANUAL_PRIZE(MoneyDirection.NEUTRAL),
    PT_PERIODIC_PRIZE(MoneyDirection.NEUTRAL),
    PT_TRANSFER_IN(MoneyDirection.NEUTRAL),
    PT_TRANSFER_OUT(MoneyDirection.NEUTRAL),
    PT_PERIODIC_CHARGE(MoneyDirection.NEUTRAL),
    PT_CLOSING_ACCOUNT(MoneyDirection.NEUTRAL),
    PT_BLOCKED_ACCOUNT(MoneyDirection.NEUTRAL),
    PT_BALANCE_BANK(MoneyDirection.NEUTRAL),
    PT_MANUAL_ADJUST(MoneyDirection.NEUTRAL),
    PT_MANUAL_ADJUST_RECONCILIATION(MoneyDirection.NEUTRAL),
    PT_REFUND(MoneyDirection.NEUTRAL),
    PT_FRAUD(MoneyDirection.NEUTRAL),
    PT_EMPTYING_INACTIVE_ACCOUNT(MoneyDirection.NEUTRAL),
    PT_SELF_EXCLUSION(MoneyDirection.NEUTRAL),

    // walLimits.limitType not already listed above (DEPOSIT and BUY are transaction types too).
    WITHDRAWAL(MoneyDirection.NEUTRAL),
    LOSS(MoneyDirection.NEUTRAL),
    ACCOUNT_BALANCE(MoneyDirection.NEUTRAL),
    FIRST_DEPOSIT(MoneyDirection.NEUTRAL);

    private static final Map<String, TransactionType> BY_CODE = Stream.of(values())
            .collect(Collectors.toUnmodifiableMap(TransactionType::code, Function.identity()));

    private final MoneyDirection direction;

    TransactionType(MoneyDirection direction) {
        this.direction = direction;
    }

    /** The stored and wire code. */
    public String code() {
        return name();
    }

    public MoneyDirection direction() {
        return direction;
    }

    /** The direction of {@code code}; NEUTRAL for a code (or null) that is not listed here. */
    public static MoneyDirection directionOf(String code) {
        TransactionType type = code == null ? null : BY_CODE.get(code);
        return type == null ? MoneyDirection.NEUTRAL : type.direction;
    }
}
