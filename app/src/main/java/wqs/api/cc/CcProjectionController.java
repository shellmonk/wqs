package wqs.api.cc;

import java.time.Instant;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import wqs.api.ProjectionParams;
import wqs.domain.ViewKind;
import wqs.read.ProjectionPage;
import wqs.read.ReadMetrics;
import wqs.read.cc.CcProjectionService;

/**
 * {@code GET /cc/v1/projection} (spec 7.1): the operator view, with {@code userId} a required
 * parameter. Deny-by-default: every request is 403 unless {@code wqs.cc.access=open} (spec 9);
 * real authentication/authorization is open question O-14.
 */
@RestController
public class CcProjectionController {

    private static final String ENDPOINT = ReadMetrics.endpoint(ViewKind.CC);

    private final CcProjectionService service;
    private final ReadMetrics metrics;

    public CcProjectionController(CcProjectionService service, ReadMetrics metrics) {
        this.service = service;
        this.metrics = metrics;
    }

    @GetMapping("/cc/v1/projection")
    public ProjectionPage projection(
            @RequestParam("userId") long userId,
            @RequestParam("from") @DateTimeFormat(iso = ISO.DATE_TIME) Instant from,
            @RequestParam("to") @DateTimeFormat(iso = ISO.DATE_TIME) Instant to,
            @RequestParam("aggregationLevel") int aggregationLevel,
            @RequestParam(name = "excludeCancelledTransactions", defaultValue = "false") boolean excludeCancelled,
            @RequestParam(name = "productTypes", required = false) List<String> productTypes,
            @RequestParam(name = "transactionTypes", required = false) List<String> transactionTypes,
            @RequestParam("page") long page,
            @RequestParam("pageSize") long pageSize) {
        long user = ProjectionParams.requireUserId(userId);
        ProjectionParams p = ProjectionParams.validate(
                from, to, aggregationLevel, excludeCancelled, productTypes, transactionTypes, page, pageSize).requireLevel0();
        return metrics.timeRequest(ENDPOINT, p.level().value(), p.page(), () -> service.level0(
                user, p.from(), p.to(), p.excludeCancelled(), p.productTypes(), p.transactionTypes(),
                p.page(), p.pageSize()));
    }
}
