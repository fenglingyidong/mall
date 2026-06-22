package com.mall.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "mall.mcp")
public class MallMcpProperties {

    private String mallBaseUrl = "http://localhost:8100";
    private Duration contextTtl = Duration.ofMinutes(30);
    private Duration confirmationTtl = Duration.ofMinutes(5);
    private Duration requestTimeout = Duration.ofSeconds(10);

    public String getMallBaseUrl() {
        return mallBaseUrl;
    }

    public void setMallBaseUrl(String mallBaseUrl) {
        this.mallBaseUrl = mallBaseUrl;
    }

    public Duration getContextTtl() {
        return contextTtl;
    }

    public void setContextTtl(Duration contextTtl) {
        this.contextTtl = contextTtl;
    }

    public Duration getConfirmationTtl() {
        return confirmationTtl;
    }

    public void setConfirmationTtl(Duration confirmationTtl) {
        this.confirmationTtl = confirmationTtl;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }
}
