package wqs.domain;

/**
 * The two projection views. Codes are persisted in counter rows (spec 5.7). Each view reads its
 * own SQL view over the single "wqPrjTransaction" table; which sources a view holds is
 * {@link SourceKind#views()}.
 */
public enum ViewKind {

    WEB(1, "wqPrjWebTransaction"),
    CC(2, "wqPrjCcTransaction");

    private final int code;
    private final String transactionView;

    ViewKind(int code, String transactionView) {
        this.code = code;
        this.transactionView = transactionView;
    }

    public int code() {
        return code;
    }

    public String transactionView() {
        return transactionView;
    }
}
