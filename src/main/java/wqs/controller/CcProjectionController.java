package wqs.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import wqs.config.ApiAccessProperties;
import wqs.dto.ProjectionResponse;
import wqs.dto.ReadPolicy;
import wqs.exception.AccessDeniedException;
import wqs.security.PlayerIds;
import wqs.service.ProjectionService;

/**
 * Customer care: any player's projection, every source. Authentication is not in place yet, so it
 * is off unless {@code wqs.api.cc-enabled=true}: then every request is 403.
 */
@Tag(name = "Customer care")
@RestController
@RequestMapping("/cc/wallet-data/rest/v1/projection")
public class CcProjectionController {

    private final ProjectionService projections;
    private final ApiAccessProperties access;

    public CcProjectionController(ProjectionService projections, ApiAccessProperties access) {
        this.projections = projections;
        this.access = access;
    }

    @Operation(summary = "A player's projection, every source",
            description = "Transactions, claimable prizes, payments, manual adjustments and limits. 403 while wqs.api.cc-enabled is off.")
    @GetMapping("/{playerId}")
    public ProjectionResponse<?> projection(
            @Parameter(description = "The player's id.", example = "1717") @PathVariable String playerId,
            @ParameterObject ProjectionParameters parameters) {
        if (!access.ccEnabled()) {
            throw new AccessDeniedException("/cc is switched off");
        }
        return projections.page(ReadPolicy.CC, PlayerIds.parse(playerId), parameters.toRequest());
    }
}
