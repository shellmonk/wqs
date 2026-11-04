package wqs.read.cc;

import org.springframework.stereotype.Component;
import wqs.read.PolicyHighLevelAssembly;
import wqs.read.ReadPolicy;
import wqs.read.ViewKind;

/** Level-1/2 assembly reads of /cc's transactions through {@link ReadPolicy#CC}. */
@Component
public class CcHighLevelAssembly extends PolicyHighLevelAssembly {

    /** What /cc shows (plan D1), bound into every statement. */
    private static final ReadPolicy POLICY = ReadPolicy.CC;

    public CcHighLevelAssembly(CcTransactionMapper mapper) {
        super(POLICY, mapper);
    }

    @Override
    public ViewKind view() {
        return ViewKind.CC;
    }
}
