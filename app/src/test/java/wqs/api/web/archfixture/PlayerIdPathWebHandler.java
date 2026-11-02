package wqs.api.web.archfixture;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/** Fixture: a /web handler taking the player id from the path (the allowlist test must reject it). */
public class PlayerIdPathWebHandler {

    @GetMapping("/web/wallet-data/rest/v1/projection/{playerId}")
    public String handle(@PathVariable("playerId") String playerId) {
        return playerId;
    }
}
