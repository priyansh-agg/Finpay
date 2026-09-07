package com.finpay.gateway.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI finpayOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("FinPay Gateway API")
                        .version("0.1.0")
                        .description("""
                                Sandboxed payment gateway + fraud engine. Merchants authenticate with
                                `X-API-Key: fp_live_...`; dashboard users with `Authorization: Bearer <jwt>`
                                from POST /v1/auth/login. Write endpoints on /v1/payments and refunds take
                                an `Idempotency-Key` header."""))
                .components(new Components()
                        .addSecuritySchemes("apiKey", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER).name("X-API-Key"))
                        .addSecuritySchemes("bearer", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")));
    }
}
