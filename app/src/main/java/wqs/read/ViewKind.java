package wqs.read;

/**
 * The two read endpoints, /web and /cc: request routing and metric tags only. Never stored -- the
 * data knows no endpoint -- and never a filter: what an endpoint shows is its {@link ReadPolicy}.
 */
public enum ViewKind {
    WEB,
    CC
}
