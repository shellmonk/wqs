package wqs.domain;

/** The two projection views. Codes are persisted in counter rows (spec 5.7). */
public enum ViewKind {

    WEB(1, "wqPrjWebTransaction"),
    CC(2, "wqPrjCcTransaction");

    private final int code;
    private final String transactionTable;

    ViewKind(int code, String transactionTable) {
        this.code = code;
        this.transactionTable = transactionTable;
    }

    public int code() {
        return code;
    }

    public String transactionTable() {
        return transactionTable;
    }
}
