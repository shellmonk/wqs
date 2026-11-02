package wqs.api.web.archfixture;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Fixture: a /web handler binding the user id from the query string (the allowlist test must reject it). */
public class UserIdParamWebHandler {

    @GetMapping("/web/wallet-data/rest/v1/projection")
    public String handle(@RequestParam("userId") long userId) {
        return String.valueOf(userId);
    }
}
