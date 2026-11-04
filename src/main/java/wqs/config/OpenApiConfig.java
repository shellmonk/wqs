package wqs.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI document at /v3/api-docs; Swagger UI at /swagger-ui.html. */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI wqsOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Wallet Query Service")
                .version("v1")
                .description("Read-only projection of the wallet: flat transactions (level 0), sessions (level 1), "
                        + "sessions with plays (level 2). Totals are exact; pageNumber is 1-based."));
    }
}
