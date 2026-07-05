package com.mall.seckill.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mall.seckill")
public class SeckillProperties {

    private int permitsPerSecond = 100;
    private long metadataCacheTtlMillis = 1000;
    private Lock lock = new Lock();
    private StockCache stockCache = new StockCache();
    private LoadTest loadTest = new LoadTest();
    private Hotspot hotspot = new Hotspot();

    public int getPermitsPerSecond() {
        return permitsPerSecond;
    }

    public void setPermitsPerSecond(int permitsPerSecond) {
        this.permitsPerSecond = permitsPerSecond;
    }

    public long getMetadataCacheTtlMillis() {
        return metadataCacheTtlMillis;
    }

    public void setMetadataCacheTtlMillis(long metadataCacheTtlMillis) {
        this.metadataCacheTtlMillis = metadataCacheTtlMillis;
    }

    public Lock getLock() {
        return lock;
    }

    public void setLock(Lock lock) {
        this.lock = lock;
    }

    public StockCache getStockCache() {
        return stockCache;
    }

    public void setStockCache(StockCache stockCache) {
        this.stockCache = stockCache;
    }

    public LoadTest getLoadTest() {
        return loadTest;
    }

    public void setLoadTest(LoadTest loadTest) {
        this.loadTest = loadTest;
    }

    public Hotspot getHotspot() {
        return hotspot;
    }

    public void setHotspot(Hotspot hotspot) {
        this.hotspot = hotspot;
    }

    public static class Lock {

        private boolean enabled = true;
        private long waitMillis = 100;
        private long leaseMillis = 0;

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

    public static class StockCache {

        private boolean enabled = false;
        private boolean failFast = true;
        private String keyPrefix = "seckill:stock-cache:";
        private Repair repair = new Repair();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isFailFast() {
            return failFast;
        }

        public void setFailFast(boolean failFast) {
            this.failFast = failFast;
        }

        public String getKeyPrefix() {
            return keyPrefix;
        }

        public void setKeyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
        }

        public Repair getRepair() {
            return repair;
        }

        public void setRepair(Repair repair) {
            this.repair = repair;
        }
    }

    public static class Repair {

        private boolean enabled = false;
        private long fixedDelay = 60000;
        private int limit = 1000;

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

        public int getLimit() {
            return limit;
        }

        public void setLimit(int limit) {
            this.limit = limit;
        }
    }

    public static class LoadTest {

        private boolean stockDeductEnabled = false;
        private boolean connectionWarmupEnabled = false;
        private int connectionWarmupSize = 100;

        public boolean isStockDeductEnabled() {
            return stockDeductEnabled;
        }

        public void setStockDeductEnabled(boolean stockDeductEnabled) {
            this.stockDeductEnabled = stockDeductEnabled;
        }

        public boolean isConnectionWarmupEnabled() {
            return connectionWarmupEnabled;
        }

        public void setConnectionWarmupEnabled(boolean connectionWarmupEnabled) {
            this.connectionWarmupEnabled = connectionWarmupEnabled;
        }

        public int getConnectionWarmupSize() {
            return connectionWarmupSize;
        }

        public void setConnectionWarmupSize(int connectionWarmupSize) {
            this.connectionWarmupSize = connectionWarmupSize;
        }
    }

    public static class Hotspot {

        private boolean enabled = false;
        private List<String> items = new ArrayList<>();
        private int permitsPerSecond = 100;
        private int maxConcurrent = 100;
        private boolean warmupEnabled = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<String> getItems() {
            return items;
        }

        public void setItems(List<String> items) {
            this.items = items == null ? new ArrayList<>() : items;
        }

        public int getPermitsPerSecond() {
            return permitsPerSecond;
        }

        public void setPermitsPerSecond(int permitsPerSecond) {
            this.permitsPerSecond = permitsPerSecond;
        }

        public int getMaxConcurrent() {
            return maxConcurrent;
        }

        public void setMaxConcurrent(int maxConcurrent) {
            this.maxConcurrent = maxConcurrent;
        }

        public boolean isWarmupEnabled() {
            return warmupEnabled;
        }

        public void setWarmupEnabled(boolean warmupEnabled) {
            this.warmupEnabled = warmupEnabled;
        }
    }
}


