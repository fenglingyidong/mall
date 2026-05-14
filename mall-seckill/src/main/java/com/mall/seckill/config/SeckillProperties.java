package com.mall.seckill.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mall.seckill")
public class SeckillProperties {

    private int permitsPerSecond = 100;
    private Lock lock = new Lock();

    public int getPermitsPerSecond() {
        return permitsPerSecond;
    }

    public void setPermitsPerSecond(int permitsPerSecond) {
        this.permitsPerSecond = permitsPerSecond;
    }

    public Lock getLock() {
        return lock;
    }

    public void setLock(Lock lock) {
        this.lock = lock;
    }

    public static class Lock {

        private boolean enabled = true;
        private long waitMillis = 100;
        private long leaseMillis = 3000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getWaitMillis() {
            return waitMillis;
        }

        public void setWaitMillis(long waitMillis) {
            this.waitMillis = waitMillis;
        }

        public long getLeaseMillis() {
            return leaseMillis;
        }

        public void setLeaseMillis(long leaseMillis) {
            this.leaseMillis = leaseMillis;
        }
    }
}


