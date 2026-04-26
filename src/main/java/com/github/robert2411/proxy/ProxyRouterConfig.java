package com.github.robert2411.proxy;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * Router configuration for the proxy. Registers route patterns that capture
 * target host and port from the URL path and delegate to ProxyHandler.
 */
@Configuration
public class ProxyRouterConfig {

    @Bean
    public RouterFunction<ServerResponse> proxyRoute(ProxyHandler handler) {
        return RouterFunctions.route()
                .route(RequestPredicates.path("/{host}/{port}/**"), handler::handle)
                .route(RequestPredicates.path("/{host}/{port}"), handler::handle)
                .build();
    }
}
