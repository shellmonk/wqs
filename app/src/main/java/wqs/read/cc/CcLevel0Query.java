package wqs.read.cc;

import org.springframework.stereotype.Component;
import wqs.read.PolicyLevel0Query;
import wqs.read.ReadPolicy;
import wqs.read.ViewKind;

/** Level-0 reads of /cc: "wqPrjTransaction" through {@link ReadPolicy#CC} (every source of players). */
@Component
public class CcLevel0Query extends PolicyLevel0Query {

    /** What /cc shows (plan D1), bound into every statement. */
    private static final ReadPolicy POLICY = ReadPolicy.CC;

    public CcLevel0Query(CcTransactionMapper mapper) {
        super(POLICY, mapper);
    }

    @Override
    public ViewKind view() {
        return ViewKind.CC;
    }
}
