package wqs.api.web;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import wqs.api.ProjectionParams;
import wqs.api.UnauthenticatedException;
import wqs.domain.AggregationLevel;
import wqs.domain.ViewKind;
import wqs.read.ProjectionResponse;
import wqs.read.ReadMetrics;
import wqs.read.web.WebProjectionService;

/**
 * {@code GET /web/wallet-data/rest/v1/projection} (api-contract). The user is the authenticated principal and nothing
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

    @GetMapping("/web/wallet-data/rest/v1/projection")
    public ProjectionResponse<?> projection(
            HttpServletRequest request,
            @RequestParam("from") @DateTimeFormat(iso = ISO.DATE_TIME) Instant from,
            @RequestParam("to") @DateTimeFormat(iso = ISO.DATE_TIME) Instant to,
            @RequestParam("aggregationLevel") int aggregationLevel,
            @RequestParam(name = "excludeCancelledTransactions", defaultValue = "false") boolean excludeCancelled,
            @RequestParam(name = "productTypes", required = false) List<String> productTypes,
            @RequestParam(name = "transactionTypes", required = false) List<String> transactionTypes,
            @RequestParam("pageNumber") int pageNumber,
            @RequestParam("pageSize") int pageSize) {
        long userId = principals.resolveUserId(request).orElseThrow(UnauthenticatedException::new);
        ProjectionParams p = ProjectionParams.validate(
                from, to, aggregationLevel, excludeCancelled, productTypes, transactionTypes, pageNumber, pageSize);
        // aggregationLevel is mandatory, so routing happens here with no fallback (spec 7.3).
        return metrics.timeRequest(ENDPOINT, p.level().value(), p.page(), () -> p.level() == AggregationLevel.L0
                ? service.level0(userId, p.from(), p.to(), p.excludeCancelled(), p.productTypes(),
                        p.transactionTypes(), p.page(), p.pageSize())
                : service.highLevel(userId, p.level(), p.from(), p.to(), p.excludeCancelled(), p.productTypes(),
                        p.transactionTypes(), p.page(), p.pageSize()));
    }
}
