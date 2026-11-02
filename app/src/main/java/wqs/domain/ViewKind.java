package wqs.domain;

/** The two projection views. Codes are persisted in counter rows (spec 5.7). */
public enum ViewKind {

    WEB(1, "wqWebItem"),
    CC(2, "wqCcItem");

    private final int code;
    private final String itemTable;

    ViewKind(int code, String itemTable) {
        this.code = code;
        this.itemTable = itemTable;
    }

    public int code() {
        return code;
    }

    public String itemTable() {
        return itemTable;
    }
}
