package wqs.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import wqs.config.ApiAccessProperties;
import wqs.controller.CcProjectionController;
import wqs.controller.ProjectionParameters;
import wqs.exception.AccessDeniedException;
import wqs.exception.UnauthenticatedException;

/** With the defaults (both switches off) neither endpoint serves anything. */
class ClosedByDefaultTest {

    private final ApiAccessProperties defaults = new ApiAccessProperties(false, false);

    @Test
    void webIgnoresThePlayerHeaderByDefault() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HeaderOwnerIdResolver.HEADER, "1-1717");
        assertThatThrownBy(() -> new HeaderOwnerIdResolver(defaults).ownerId(request))
                .isInstanceOf(UnauthenticatedException.class);
    }

    @Test
    void ccIsDeniedByDefault() {
        CcProjectionController controller = new CcProjectionController(null, defaults);
        assertThatThrownBy(() -> controller.projection("1717",
                new ProjectionParameters(null, null, null, null, null, null, null, null)))
                .isInstanceOf(AccessDeniedException.class);
    }
}
