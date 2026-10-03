package com.dtc.transit.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

/**
 * API metadata and the security scheme for the generated OpenAPI document.
 *
 * <p>Springdoc discovers the endpoints on its own. What it cannot infer is how to authenticate: without the
 * bearer scheme declared here the Swagger UI has no Authorize button, so every try-it-out request goes out
 * unauthenticated and comes back 401. That makes the generated documentation look broken when it is the
 * documentation of the authentication that is missing.
 *
 * <p>The scheme is declared as a global requirement rather than per endpoint. Every path except login, refresh
 * and the health probes requires a token, so stating it once is both shorter and more accurate than annotating
 * each controller.
 */
@Configuration
public class OpenApiConfig {

    /** The scheme name, referenced by the global security requirement below. */
    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI dtcOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("DTC Automated Bus Scheduling and Route Management API")
                        .version("v1")
                        .description(
                                """
                                Vehicle and crew scheduling for a bus network: master data, routes with PostGIS \
                                geometry, timetables and trips, vehicle blocks, crew duties, conflict detection, \
                                publication, reports and an audit trail.

                                Obtain a token from `POST /api/v1/auth/login`, then use Authorize above. Access \
                                tokens last 15 minutes; refresh with `POST /api/v1/auth/refresh`.

                                Times are service-day seconds from the service-day start (03:00 IST by default), \
                                so a value above 86,400 means the following calendar day while still belonging \
                                to the earlier service date.
                                """)
                        .contact(new Contact().name("DTC Scheduling")))
                .components(new Components()
                        .addSecuritySchemes(
                                BEARER_SCHEME,
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description(
                                                "RS256 access token from /api/v1/auth/login. The algorithm is "
                                                        + "pinned, so an unsigned or HS256 token is refused.")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
