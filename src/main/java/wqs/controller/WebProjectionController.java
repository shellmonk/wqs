package wqs.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import wqs.dto.ProjectionResponse;
import wqs.dto.ReadPolicy;
import wqs.security.PlayerIdResolver;
import wqs.service.ProjectionService;

/** The player's own projection: transactions and claimable prizes. The player comes from the request's identity, never a parameter. */
@RestController
@RequestMapping("/web/wallet-data/rest/v1/projection")
public class WebProjectionController {

    private final ProjectionService projections;
    private final PlayerIdResolver players;

    public WebProjectionController(ProjectionService projections, PlayerIdResolver players) {
        this.projections = projections;
        this.players = players;
    }

    @GetMapping
    public ProjectionResponse<?> projection(HttpServletRequest request, ProjectionParameters parameters) {
        return projections.page(ReadPolicy.WEB, players.playerId(request), parameters.toRequest());
    }
}
