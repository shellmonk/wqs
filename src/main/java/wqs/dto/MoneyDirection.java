package wqs.dto;

/** What a transaction type does to session and play totals: adds to the played total, the won total, or neither. */
public enum MoneyDirection {

    NEUTRAL,
    PLAY,
    WIN
}
