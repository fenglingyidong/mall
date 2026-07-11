package com.mall.order.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mall")
public class OrderProperties {

    private Order order = new Order();

    public Order getOrder() {
        return order;
    }

    public void setOrder(Order order) {
        this.order = order;
    }

    public static class Order {
        private long closeDelaySeconds = 30;
        private ExpiredClose expiredClose = new ExpiredClose();

        public long getCloseDelaySeconds() {
            return closeDelaySeconds;
        }

        public void setCloseDelaySeconds(long closeDelaySeconds) {
            this.closeDelaySeconds = closeDelaySeconds;
        }

        public ExpiredClose getExpiredClose() {
            return expiredClose;
        }

        public void setExpiredClose(ExpiredClose expiredClose) {
            this.expiredClose = expiredClose == null ? new ExpiredClose() : expiredClose;
        }
    }

    public static class ExpiredClose {
        private boolean enabled = true;
        private long fixedDelay = 5000;
        private int batchSize = 500;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getFixedDelay() {
            return fixedDelay;
        }

        public void setFixedDelay(long fixedDelay) {
            this.fixedDelay = fixedDelay;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }
    }
}


