package wqs.read.web;

import org.springframework.stereotype.Component;
import wqs.read.PolicyLevel0Query;
import wqs.read.ReadPolicy;
import wqs.read.ViewKind;

/** Level-0 reads of /web: "wqPrjTransaction" through {@link ReadPolicy#WEB} (transactions and claimable prizes of players). */
@Component
public class WebLevel0Query extends PolicyLevel0Query {

    /** What /web shows (plan D1), bound into every statement. */
    private static final ReadPolicy POLICY = ReadPolicy.WEB;

    public WebLevel0Query(WebTransactionMapper mapper) {
        super(POLICY, mapper);
    }

    @Override
    public ViewKind view() {
        return ViewKind.WEB;
    }
}
