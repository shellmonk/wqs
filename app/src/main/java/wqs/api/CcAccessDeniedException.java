package wqs.api;

/** /cc is disabled on this deployment (403, wqs.cc.access=deny, O-14). */
public class CcAccessDeniedException extends RuntimeException {

    public CcAccessDeniedException() {
        super("/cc access is denied (wqs.cc.access=deny)");
    }
}
