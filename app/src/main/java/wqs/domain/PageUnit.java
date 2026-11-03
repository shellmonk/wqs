package wqs.domain;

/**
 * What a page counts. Only two values exist: levels 1 and 2 page over the same unit -- a
 * high-level row -- and differ only in payload nesting, so they share counters entirely and
 * always report identical page counts (spec 5.7).
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
