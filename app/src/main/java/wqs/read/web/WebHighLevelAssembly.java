package wqs.read.web;

import org.springframework.stereotype.Component;
import wqs.read.PolicyHighLevelAssembly;
import wqs.read.ReadPolicy;
import wqs.read.ViewKind;

/** Level-1/2 assembly reads of /web's transactions through {@link ReadPolicy#WEB}. */
@Component
public class WebHighLevelAssembly extends PolicyHighLevelAssembly {

    /** What /web shows (plan D1), bound into every statement. */
    private static final ReadPolicy POLICY = ReadPolicy.WEB;

    public WebHighLevelAssembly(WebTransactionMapper mapper) {
        super(POLICY, mapper);
    }

    @Override
    public ViewKind view() {
        return ViewKind.WEB;
    }
}
