package wqs.domain;

/**
 * Whether a transaction type contributes to the wagered total, the won total, or neither.
 *
 * The full type list is open (O-13); these are the codes exercised today. Extend the switch
 * when the real enumeration arrives -- the default is deliberately NEUTRAL rather than a throw,
 * so an unknown type is projected and visible rather than dead-lettered.
 */
public enum MoneyDirection {

    PLAY,
    WIN,
    NEUTRAL;

    public static MoneyDirection ofType(short type) {
        return switch (type) {
            case 1 -> PLAY;
            case 2 -> WIN;
            default -> NEUTRAL;
        };
    }
}
