package io.github.lachin12.oms.api;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {
    @Bean
    OpenAPI omsOpenApi() {
        return new OpenAPI().info(new Info().title("Trading OMS API").version("v1")
                .description("Simulated order management. Order acceptance is separate from execution. No broker or exchange connectivity."));
    }
}
