package wqs.read.cc;

import org.springframework.stereotype.Component;
import wqs.read.PolicyHighLevelQuery;
import wqs.read.ReadPolicy;
import wqs.read.ViewKind;

/** Level-1/2 entries of /cc: sessions merged with standalone rows through {@link ReadPolicy#CC} (plan D4). */
@Component
public class CcHighLevelQuery extends PolicyHighLevelQuery {

    /** What /cc shows (plan D1), bound into every statement. */
    private static final ReadPolicy POLICY = ReadPolicy.CC;

    public CcHighLevelQuery() {
        super(POLICY);
    }

    @Override
    public ViewKind view() {
        return ViewKind.CC;
    }
}
