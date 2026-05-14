package com.mall.product.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mall.canal")
public class CanalProperties {

    private boolean enabled = true;
    private String host = "localhost";
    private int port = 11111;
    private String destination = "example";
    private String username = "";
    private String password = "";
    private String subscribe = "mall\\.(sku|sku_stock|spu|brand|category)";
    private int batchSize = 100;
    private long idleSleepMillis = 1000;
    private long retrySleepMillis = 5000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getDestination() {
        return destination;
    }

    public void setDestination(String destination) {
        this.destination = destination;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getSubscribe() {
        return subscribe;
    }

    public void setSubscribe(String subscribe) {
        this.subscribe = subscribe;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public long getIdleSleepMillis() {
        return idleSleepMillis;
    }

    public void setIdleSleepMillis(long idleSleepMillis) {
        this.idleSleepMillis = idleSleepMillis;
    }

    public long getRetrySleepMillis() {
        return retrySleepMillis;
    }

    public void setRetrySleepMillis(long retrySleepMillis) {
        this.retrySleepMillis = retrySleepMillis;
    }
}
