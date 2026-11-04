package wqs.read.web;

import org.springframework.stereotype.Component;
import wqs.read.PolicyHighLevelQuery;
import wqs.read.ReadPolicy;
import wqs.read.ViewKind;

/** Level-1/2 entries of /web: sessions merged with standalone rows through {@link ReadPolicy#WEB} (plan D4). */
@Component
public class WebHighLevelQuery extends PolicyHighLevelQuery {

    /** What /web shows (plan D1), bound into every statement. */
    private static final ReadPolicy POLICY = ReadPolicy.WEB;

    public WebHighLevelQuery() {
        super(POLICY);
    }

    @Override
    public ViewKind view() {
        return ViewKind.WEB;
    }
}
