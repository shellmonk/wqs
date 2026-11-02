package wqs.api.cc;

import java.time.Instant;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import wqs.api.ProjectionParams;
import wqs.domain.AggregationLevel;
import wqs.domain.ViewKind;
import wqs.read.ProjectionResponse;
import wqs.read.ReadMetrics;
import wqs.read.cc.CcProjectionService;

/**
 * {@code GET /cc/wallet-data/rest/v1/projection/{playerId}} (api-contract): the operator view,
 * with the player in the path. Deny-by-default: every request is 403 unless {@code wqs.cc.access=open} (spec 9);
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

    @GetMapping("/cc/wallet-data/rest/v1/projection/{playerId}")
    public ProjectionResponse<?> projection(
            @PathVariable("playerId") String playerId,
            @RequestParam("from") @DateTimeFormat(iso = ISO.DATE_TIME) Instant from,
            @RequestParam("to") @DateTimeFormat(iso = ISO.DATE_TIME) Instant to,
            @RequestParam("aggregationLevel") int aggregationLevel,
            @RequestParam(name = "excludeCancelledTransactions", defaultValue = "false") boolean excludeCancelled,
            @RequestParam(name = "productTypes", required = false) List<String> productTypes,
            @RequestParam(name = "transactionTypes", required = false) List<String> transactionTypes,
            @RequestParam("pageNumber") int pageNumber,
            @RequestParam("pageSize") int pageSize) {
        long user = ProjectionParams.parsePlayerId(playerId);
        ProjectionParams p = ProjectionParams.validate(
                from, to, aggregationLevel, excludeCancelled, productTypes, transactionTypes, pageNumber, pageSize);
        // aggregationLevel is mandatory, so routing happens here with no fallback (spec 7.3).
        return metrics.timeRequest(ENDPOINT, p.level().value(), p.page(), () -> p.level() == AggregationLevel.L0
                ? service.level0(user, p.from(), p.to(), p.excludeCancelled(), p.productTypes(),
                        p.transactionTypes(), p.page(), p.pageSize())
                : service.highLevel(user, p.level(), p.from(), p.to(), p.excludeCancelled(), p.productTypes(),
                        p.transactionTypes(), p.page(), p.pageSize()));
    }
}
