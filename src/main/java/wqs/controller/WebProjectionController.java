package wqs.controller;

import jakarta.servlet.http.HttpServletRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import wqs.dto.ProjectionResponse;
import wqs.dto.ReadPolicy;
import wqs.security.PlayerIdResolver;
import wqs.service.ProjectionService;

/** The player's own projection: transactions and claimable prizes. The player comes from the request's identity, never a parameter. */
@Tag(name = "Player")
@RestController
@RequestMapping("/web/wallet-data/rest/v1/projection")
public class WebProjectionController {

    private final ProjectionService projections;
    private final PlayerIdResolver players;

    public WebProjectionController(ProjectionService projections, PlayerIdResolver players) {
        this.projections = projections;
        this.players = players;
    }

    @Operation(summary = "The signed-in player's projection",
            description = "Transactions and claimable prizes. The player comes from the request's identity: for now the "
                    + "placeholder X-Player-Id header, honoured only while wqs.api.web-player-header is on (otherwise 401).")
    @Parameter(name = "X-Player-Id", in = ParameterIn.HEADER, description = "PLACEHOLDER until token auth: the player's id.",
            example = "1717")
    @GetMapping
    public ProjectionResponse<?> projection(HttpServletRequest request, @ParameterObject ProjectionParameters parameters) {
        return projections.page(ReadPolicy.WEB, players.playerId(request), parameters.toRequest());
    }
}
