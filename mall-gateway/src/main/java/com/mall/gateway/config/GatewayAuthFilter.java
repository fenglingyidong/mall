package com.mall.gateway.config;

import com.mall.common.exception.BusinessException;
import com.mall.gateway.pojo.vo.GatewayUser;
import com.mall.gateway.service.GatewayTokenService;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
public class GatewayAuthFilter implements GlobalFilter, Ordered {

    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String USERNAME_HEADER = "X-Username";
    private static final List<String> PUBLIC_PATHS = List.of(
            "/api/auth/login",
            "/api/product",
            "/api/seckill/activities"
    );

    private final GatewayTokenService tokenService;

    public GatewayAuthFilter(GatewayTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        if (PUBLIC_PATHS.stream().anyMatch(path::startsWith)) {
            return chain.filter(exchange);
        }
        try {
            GatewayUser user = tokenService.verify(exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
            ServerHttpRequest request = exchange.getRequest().mutate()
                    .header(USER_ID_HEADER, String.valueOf(user.userId()))
                    .header(USERNAME_HEADER, user.username())
                    .build();
            return chain.filter(exchange.mutate().request(request).build());
        } catch (BusinessException exception) {
            return writeError(exchange, exception.code(), exception.getMessage());
        }
    }

    @Override
    public int getOrder() {
        return -100;
    }

    private Mono<Void> writeError(ServerWebExchange exchange, int code, String message) {
        String safeMessage = message == null ? "Gateway error" : message.replace("\"", "'");
        byte[] bytes = ("{\"code\":" + code + ",\"message\":\"" + safeMessage + "\",\"data\":null}")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponse().setStatusCode(HttpStatus.OK);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
