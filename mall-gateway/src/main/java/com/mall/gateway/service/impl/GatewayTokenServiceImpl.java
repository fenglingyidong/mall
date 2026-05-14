package com.mall.gateway.service.impl;

import com.mall.common.exception.BusinessException;
import com.mall.gateway.pojo.vo.GatewayUser;
import com.mall.gateway.service.GatewayTokenService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

@Service
public class GatewayTokenServiceImpl implements GatewayTokenService {

    private final String secret;

    public GatewayTokenServiceImpl(@Value("${mall.auth.secret:mall-demo-secret}") String secret) {
        this.secret = secret;
    }

    @Override
    public GatewayUser verify(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new BusinessException(401, "Login required");
        }
        try {
            String token = authorization.substring("Bearer ".length()).trim();
            String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            String[] parts = raw.split(":", 4);
            if (parts.length != 4) {
                throw new BusinessException(401, "Invalid token");
            }
            String payload = parts[0] + ":" + parts[1] + ":" + parts[2];
            if (!sign(payload).equals(parts[3])) {
                throw new BusinessException(401, "Invalid token signature");
            }
            if (Long.parseLong(parts[2]) < Instant.now().getEpochSecond()) {
                throw new BusinessException(401, "Token expired");
            }
            return new GatewayUser(Long.parseLong(parts[0]), parts[1]);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(401, "Invalid token");
        }
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new BusinessException(500, "Token signing failed");
        }
    }
}
