package com.mall.mcp.context;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.mall.mcp.client.MallGatewayClient;
import com.mall.mcp.config.MallMcpProperties;
import com.mall.mcp.model.LoginResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

@Service
public class MallContextService {

    private final MallMcpProperties properties;
    private final MallGatewayClient mallGatewayClient;
    private final Clock clock;
    private final Cache<String, MallUserContext> contexts;

    @Autowired
    public MallContextService(MallMcpProperties properties, MallGatewayClient mallGatewayClient) {
        this(properties, mallGatewayClient, Clock.systemUTC());
    }

    MallContextService(MallMcpProperties properties, MallGatewayClient mallGatewayClient, Clock clock) {
        this.properties = properties;
        this.mallGatewayClient = mallGatewayClient;
        this.clock = clock;
        this.contexts = Caffeine.newBuilder()
                .expireAfterWrite(properties.getContextTtl())
                .ticker(() -> clock.millis() * 1_000_000)
                .build();
    }

    public Optional<MallUserContext> findValid(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return Optional.empty();
        }
        String key = sessionId.trim();
        MallUserContext context = contexts.getIfPresent(key);
        if (context == null) {
            return Optional.empty();
        }
        if (!context.expiresAt().isAfter(Instant.now(clock))) {
            contexts.invalidate(key);
            return Optional.empty();
        }
        return Optional.of(context);
    }

    public Optional<String> resolveAuthorization(String sessionId) {
        return resolveAuthorization(sessionId, Map.of());
    }

    public Optional<String> resolveAuthorization(String sessionId, Map<String, Object> requestMeta) {
        String metaToken = readMetaText(requestMeta, "mallToken", "mallAuthorization", "authorization");
        if (StringUtils.hasText(metaToken)) {
            cacheMetaContext(sessionId, requestMeta, metaToken);
            return Optional.of(normalizeToken(metaToken));
        }

        Optional<MallUserContext> contextOptional = findValid(sessionId);
        if (contextOptional.isEmpty()) {
            String username = readMetaText(requestMeta, "mallUsername");
            String password = readMetaText(requestMeta, "mallPassword");
            if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
                return Optional.empty();
            }
            return loginAndCache(sessionId, requestMeta, username, password);
        }
        MallUserContext context = contextOptional.get();
        if (StringUtils.hasText(context.mallToken())) {
            return Optional.of(normalizeToken(context.mallToken()));
        }
        if (!StringUtils.hasText(context.mallUsername()) || !StringUtils.hasText(context.mallPassword())) {
            return Optional.empty();
        }
        Optional<LoginResponse> loginResponse = mallGatewayClient.login(context.mallUsername(), context.mallPassword());
        if (loginResponse.isEmpty() || !StringUtils.hasText(loginResponse.get().token())) {
            return Optional.empty();
        }
        String token = normalizeToken(loginResponse.get().token());
        contexts.put(context.sessionId(), context.withToken(token, Instant.now(clock).plus(properties.getContextTtl())));
        return Optional.of(token);
    }

    private Optional<String> loginAndCache(String sessionId,
                                           Map<String, Object> requestMeta,
                                           String username,
                                           String password) {
        Optional<LoginResponse> loginResponse = mallGatewayClient.login(username, password);
        if (loginResponse.isEmpty() || !StringUtils.hasText(loginResponse.get().token())) {
            return Optional.empty();
        }
        String token = normalizeToken(loginResponse.get().token());
        if (StringUtils.hasText(sessionId)) {
            contexts.put(sessionId.trim(), new MallUserContext(
                    sessionId.trim(),
                    readMetaText(requestMeta, "userId"),
                    token,
                    username.trim(),
                    password,
                    Instant.now(clock).plus(properties.getContextTtl())
            ));
        }
        return Optional.of(token);
    }

    private void cacheMetaContext(String sessionId, Map<String, Object> requestMeta, String token) {
        if (!StringUtils.hasText(sessionId)) {
            return;
        }
        contexts.put(sessionId.trim(), new MallUserContext(
                sessionId.trim(),
                readMetaText(requestMeta, "userId"),
                normalizeToken(token),
                readMetaText(requestMeta, "mallUsername"),
                readMetaText(requestMeta, "mallPassword"),
                Instant.now(clock).plus(properties.getContextTtl())
        ));
    }

    private String readMetaText(Map<String, Object> requestMeta, String... keys) {
        if (requestMeta == null || requestMeta.isEmpty() || keys == null) {
            return "";
        }
        for (String key : keys) {
            if (!StringUtils.hasText(key)) {
                continue;
            }
            Object value = requestMeta.get(key);
            if (value != null && StringUtils.hasText(value.toString())) {
                return value.toString().trim();
            }
        }
        return "";
    }

    private String normalizeToken(String token) {
        if (!StringUtils.hasText(token)) {
            return "";
        }
        String trimmed = token.trim();
        return trimmed.startsWith("Bearer ") ? trimmed : "Bearer " + trimmed;
    }

}
