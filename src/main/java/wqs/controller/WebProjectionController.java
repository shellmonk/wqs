package wqs.controller;

import jakarta.servlet.http.HttpServletRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import wqs.dto.ProjectionResponse;
import wqs.dto.ReadPolicy;
import wqs.security.OwnerIdResolver;
import wqs.service.ProjectionService;

/** The caller's own projection: transactions and claimable prizes. The owner comes from the request's identity, never a parameter. */
@Tag(name = "Player")
@RestController
@RequestMapping("/web/wallet-data/rest/v1/projection")
public class WebProjectionController {

    private final ProjectionService projectionService;
    private final OwnerIdResolver ownerIdResolver;

    public WebProjectionController(ProjectionService projectionService, OwnerIdResolver ownerIdResolver) {
        this.projectionService = projectionService;
        this.ownerIdResolver = ownerIdResolver;
    }

    @Operation(summary = "The signed-in owner's projection",
            description = "Transactions and claimable prizes. The player comes from the request's identity: for now the "
                    + "placeholder X-Owner-Id header, honoured only while wqs.api.web-owner-header is on (otherwise 401).")
    @Parameter(name = "X-Owner-Id", in = ParameterIn.HEADER, description = "PLACEHOLDER until token auth: the owner's id, \"{type}-{id}\".",
            example = "1-1717")
    @GetMapping
    public ResponseEntity<ProjectionResponse<?>> projection(HttpServletRequest request,
                                                            @ParameterObject ProjectionParameters parameters) {
        return ResponseEntity.ok(
                projectionService.page(ReadPolicy.WEB, ownerIdResolver.ownerId(request), parameters.toRequest()));
    }
}
