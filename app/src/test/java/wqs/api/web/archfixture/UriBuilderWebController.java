package wqs.api.web.archfixture;

import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** Fixture: reaches the current request's query parameters through ServletUriComponentsBuilder. */
public class UriBuilderWebController {
    public Object leak() {
        return ServletUriComponentsBuilder.fromCurrentRequest().build().getQueryParams().getFirst("userId");
    }
}
