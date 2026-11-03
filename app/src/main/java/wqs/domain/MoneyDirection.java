package wqs.domain;

/**
 * Whether a transaction type contributes to the wagered total, the won total, or neither.
 *
 * A type's direction is code: {@link TransactionType} holds it per known code (plan D1), and
 * {@link TransactionType#directionOf(String)} treats an unknown code as NEUTRAL, so it is projected
 * and visible rather than dead-lettered.
 */
public enum MoneyDirection {

    NEUTRAL,
    PLAY,
    WIN
}
