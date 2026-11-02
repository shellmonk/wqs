package wqs.domain;

import java.util.Set;

/**
 * The five ingestion sources and which views each feeds (spec section 4).
 *
 * /web carries transactions and claimable prizes only; /cc carries all five. Routing lives here
 * so there is exactly one place that decides it.
 */
public enum SourceKind {

    TRANSACTION(1, Set.of(ViewKind.WEB, ViewKind.CC)),
    MANUAL_ADJUSTMENT(2, Set.of(ViewKind.CC)),
    CLAIMABLE_PRIZE(3, Set.of(ViewKind.WEB, ViewKind.CC)),
    PAYMENT(4, Set.of(ViewKind.CC)),
    LIMIT(5, Set.of(ViewKind.CC));

    private final int code;
    private final Set<ViewKind> views;

    SourceKind(int code, Set<ViewKind> views) {
        this.code = code;
        this.views = views;
    }

    public int code() {
        return code;
    }

    public Set<ViewKind> views() {
        return views;
    }

    public static SourceKind ofCode(int code) {
        for (SourceKind kind : values()) {
            if (kind.code == code) {
                return kind;
            }
        }
        throw new IllegalArgumentException("unknown source code: " + code);
    }
}
