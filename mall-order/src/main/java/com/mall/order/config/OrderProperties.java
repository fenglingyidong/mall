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

        public long getCloseDelaySeconds() {
            return closeDelaySeconds;
        }

        public void setCloseDelaySeconds(long closeDelaySeconds) {
            this.closeDelaySeconds = closeDelaySeconds;
        }
    }
}


