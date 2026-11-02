package wqs.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import wqs.api.cc.CcAccessConfig;
import wqs.api.cc.CcAccessProperties;
import wqs.api.web.PrincipalConfig;
import wqs.api.web.PrincipalResolver;
import wqs.api.web.WebPrincipalProperties;

/**
 * Fail-closed configuration: a security setting that is present but unusable stops the context
 * instead of falling back to a default the deployment did not choose.
 */
@ExtendWith(OutputCaptureExtension.class)
class ConfigBindingTest {

    @Configuration
    @EnableConfigurationProperties({WebPrincipalProperties.class, CcAccessProperties.class})
    @Import({PrincipalConfig.class, CcAccessConfig.class})
    static class Cfg {}

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Cfg.class);

    @Test
    void unset_header_defaults_to_the_gateway_header() {
        runner.withPropertyValues("wqs.web.principal.mode=trusted-header").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(WebPrincipalProperties.class).header()).isEqualTo("X-Authenticated-User-Id");
            assertThat(ctx).hasSingleBean(PrincipalResolver.class);
        });
    }

    /** A blank env var must not silently switch WQS to a header the gateway does not strip. */
    @Test
    void blank_header_in_trusted_header_mode_fails_startup() {
        runner.withPropertyValues("wqs.web.principal.mode=trusted-header", "wqs.web.principal.header=")
                .run(ctx -> assertThat(ctx).hasFailed());
        runner.withPropertyValues("wqs.web.principal.mode=trusted-header", "wqs.web.principal.header=   ")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void non_token_header_fails_startup() {
        for (String bad : new String[] {"X User", "X-User:", "X-User/", "X(User)", "X-Üser", "X,Y"}) {
            runner.withPropertyValues("wqs.web.principal.mode=trusted-header", "wqs.web.principal.header=" + bad)
                    .run(ctx -> assertThat(ctx).as(bad).hasFailed());
        }
        runner.withPropertyValues("wqs.web.principal.mode=trusted-header", "wqs.web.principal.header=X-Gw_User.1~")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void unknown_principal_mode_fails_startup() {
        runner.withPropertyValues("wqs.web.principal.mode=jwt").run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void cc_access_defaults_to_deny_and_unknown_value_fails_startup() {
        runner.run(ctx -> assertThat(ctx.getBean(CcAccessProperties.class).access())
                .isEqualTo(CcAccessProperties.Access.DENY));
        runner.withPropertyValues("wqs.cc.access=open").run(ctx -> assertThat(ctx.getBean(CcAccessProperties.class)
                .access()).isEqualTo(CcAccessProperties.Access.OPEN));
        runner.withPropertyValues("wqs.cc.access=maybe").run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void cc_open_mode_warns_at_startup(CapturedOutput output) {
        runner.withPropertyValues("wqs.cc.access=open").run(ctx -> assertThat(ctx).hasNotFailed());
        assertThat(output.getOut()).contains("WARN").contains("/cc access is OPEN");
    }

    @Test
    void cc_deny_mode_does_not_warn(CapturedOutput output) {
        runner.run(ctx -> assertThat(ctx).hasNotFailed());
        assertThat(output.getOut()).doesNotContain("/cc access is OPEN");
    }

    /** D3/D7: which product types are eInstant; the default is the stream's "instants". */
    @Test
    void einstant_product_types_default_to_instants_and_a_blank_entry_fails_startup() {
        ApplicationContextRunner einstant = new ApplicationContextRunner()
                .withUserConfiguration(EInstantCfg.class);
        einstant.run(ctx -> assertThat(ctx.getBean(wqs.projection.EInstantProperties.class).productTypes())
                .containsExactly("instants"));
        einstant.withPropertyValues("wqs.einstant.product-types=instants,scratch")
                .run(ctx -> assertThat(ctx.getBean(wqs.projection.EInstantProperties.class).isEInstant("scratch"))
                        .isTrue());
        einstant.withPropertyValues("wqs.einstant.product-types[0]=instants", "wqs.einstant.product-types[1]= ")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Configuration
    @EnableConfigurationProperties(wqs.projection.EInstantProperties.class)
    static class EInstantCfg {}
}
