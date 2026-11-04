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
import wqs.exception.InvalidParameterException;
import wqs.util.OwnerIds;
import wqs.service.ProjectionService;

/**
 * Customer care: any owner's projection, every source. Authentication is not in place yet, so it
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

    @Operation(summary = "An owner's projection, every source",
            description = "Transactions, claimable prizes, payments, manual adjustments and limits. 403 while wqs.api.cc-enabled is off.")
    @GetMapping("/{ownerId}")
    public ProjectionResponse<?> projection(
            @Parameter(description = "The owner's id, \"{type}-{id}\".", example = "1-1717") @PathVariable String ownerId,
            @ParameterObject ProjectionParameters parameters) {
        if (!access.ccEnabled()) {
            throw new AccessDeniedException("/cc is switched off");
        }
        String owner = OwnerIds.canonical(ownerId)
                .orElseThrow(() -> new InvalidParameterException("ownerId", "must be \"{type}-{id}\""));
        return projections.page(ReadPolicy.CC, owner, parameters.toRequest());
    }
}
