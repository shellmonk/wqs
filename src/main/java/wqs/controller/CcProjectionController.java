package wqs.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import wqs.dto.ProjectionResponse;
import wqs.dto.ReadPolicy;
import wqs.security.PlayerIds;
import wqs.service.ProjectionService;

/** Customer care: any player's projection, every source. Authentication is not in place yet. */
@RestController
@RequestMapping("/cc/wallet-data/rest/v1/projection")
public class CcProjectionController {

    private final ProjectionService projections;

    public CcProjectionController(ProjectionService projections) {
        this.projections = projections;
    }

    @GetMapping("/{playerId}")
    public ProjectionResponse<?> projection(@PathVariable String playerId, ProjectionParameters parameters) {
        return projections.page(ReadPolicy.CC, PlayerIds.parse(playerId), parameters.toRequest());
    }
}
