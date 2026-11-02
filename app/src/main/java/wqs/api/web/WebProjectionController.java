package wqs.api.web;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import wqs.api.ProjectionParams;
import wqs.api.UnauthenticatedException;
import wqs.domain.ViewKind;
import wqs.read.ProjectionPage;
import wqs.read.ReadMetrics;
import wqs.read.web.WebProjectionService;

/**
 * {@code GET /web/v1/projection} (spec 7.1). The user is the authenticated principal and nothing
 * else (spec 9): there is deliberately NO userId parameter, and nothing in this package reads raw
 * request parameters (ArchitectureTest.web_layer_never_reads_raw_request_parameters). The request
 * object is passed only to the {@link PrincipalResolver}.
 */
@RestController
public class WebProjectionController {

    private static final String ENDPOINT = ReadMetrics.endpoint(ViewKind.WEB);

    private final PrincipalResolver principals;
    private final WebProjectionService service;
    private final ReadMetrics metrics;

    public WebProjectionController(PrincipalResolver principals, WebProjectionService service, ReadMetrics metrics) {
        this.principals = principals;
        this.service = service;
        this.metrics = metrics;
    }

    @GetMapping("/web/v1/projection")
    public ProjectionPage projection(
            HttpServletRequest request,
            @RequestParam("from") @DateTimeFormat(iso = ISO.DATE_TIME) Instant from,
            @RequestParam("to") @DateTimeFormat(iso = ISO.DATE_TIME) Instant to,
            @RequestParam("aggregationLevel") int aggregationLevel,
            @RequestParam(name = "excludeCancelledTransactions", defaultValue = "false") boolean excludeCancelled,
            @RequestParam("page") long page,
            @RequestParam("pageSize") long pageSize) {
        long userId = principals.resolveUserId(request).orElseThrow(UnauthenticatedException::new);
        ProjectionParams p = ProjectionParams.validate(
                from, to, aggregationLevel, excludeCancelled, page, pageSize).requireLevel0();
        return metrics.timeRequest(ENDPOINT, p.level().value(), p.page(), () -> service.level0(
                userId, p.from(), p.to(), p.excludeCancelled(), p.page(), p.pageSize()));
    }
}
