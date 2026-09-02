package com.bustix.api.v1;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI document for the partner-facing {@code /v1} surface. Served at
 * {@code /v3/api-docs/partner-v1} (JSON) and rendered by Swagger UI at
 * {@code /swagger-ui.html} - both permitAll'd in {@code SecurityConfig}.
 *
 * The whole surface authenticates with an OAuth2 client-credentials bearer
 * token (see the Partner API Build Plan); this declares that scheme so the
 * generated docs and "Try it out" reflect it.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public GroupedOpenApi partnerV1Api() {
        return GroupedOpenApi.builder()
                .group("partner-v1")
                .pathsToMatch("/v1/**")
                .build();
    }

    @Bean
    public OpenAPI partnerOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Bustix Partner API")
                        .version("v1")
                        .description("Server-to-server integration for a bus operator. "
                                + "Authenticate with the OAuth2 client-credentials grant using the "
                                + "client id and secret issued to your integration."))
                .components(new Components().addSecuritySchemes("bearerAuth", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}
