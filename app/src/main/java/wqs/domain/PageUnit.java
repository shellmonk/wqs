package wqs.domain;

/**
 * What a page counts, stored as "wqOwnerMonth"."unit" (plan D5): TRANSACTION (TX, level 0) or
 * HIGH_LEVEL (ENTRY, levels 1 and 2 -- a session or a standalone transaction). Levels 1 and 2 page over
 * the same entries and differ only in payload nesting, so they always report identical page counts.
 */
public enum PageUnit {

    TRANSACTION((short) 0),
    HIGH_LEVEL((short) 1);

    private final short code;

    PageUnit(short code) {
        this.code = code;
    }

    public short code() {
        return code;
    }
}
