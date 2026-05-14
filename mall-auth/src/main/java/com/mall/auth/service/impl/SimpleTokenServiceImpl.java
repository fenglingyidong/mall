package com.mall.auth.service.impl;

import com.mall.auth.pojo.vo.TokenPayload;
import com.mall.auth.service.SimpleTokenService;
import com.mall.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

@Service
public class SimpleTokenServiceImpl implements SimpleTokenService {

    private final String secret;

    public SimpleTokenServiceImpl(@Value("${mall.auth.secret:mall-demo-secret}") String secret) {
        this.secret = secret;
    }

    @Override
    public String create(Long userId, String username) {
        long expireAt = Instant.now().plusSeconds(86400).getEpochSecond();
        String payload = userId + ":" + username + ":" + expireAt;
        String signature = sign(payload);
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((payload + ":" + signature).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public TokenPayload verify(String token) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            String[] parts = raw.split(":", 4);
            if (parts.length != 4) {
                throw new BusinessException(401, "Invalid token");
            }
            String payload = parts[0] + ":" + parts[1] + ":" + parts[2];
            if (!sign(payload).equals(parts[3])) {
                throw new BusinessException(401, "Invalid token signature");
            }
            long expireAt = Long.parseLong(parts[2]);
            if (expireAt < Instant.now().getEpochSecond()) {
                throw new BusinessException(401, "Token expired");
            }
            return new TokenPayload(Long.parseLong(parts[0]), parts[1], expireAt);
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
