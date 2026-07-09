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
    private ReservationGuard reservationGuard = new ReservationGuard();
    private ResultRetry resultRetry = new ResultRetry();
    private LoadTest loadTest = new LoadTest();
    private Hotspot hotspot = new Hotspot();
    private Bucket bucket = new Bucket();

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

    public ReservationGuard getReservationGuard() {
        return reservationGuard;
    }

    public void setReservationGuard(ReservationGuard reservationGuard) {
        this.reservationGuard = reservationGuard;
    }

    public ResultRetry getResultRetry() {
        return resultRetry;
    }

    public void setResultRetry(ResultRetry resultRetry) {
        this.resultRetry = resultRetry;
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

    public Bucket getBucket() {
        return bucket;
    }

    public void setBucket(Bucket bucket) {
        this.bucket = bucket;
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

    public static class ReservationGuard {

        private boolean enabled = false;
        private long processingProbeAfterSeconds = 30;
        private long safeReleaseAfterSeconds = 300;
        private int repairBatchSize = 100;
        private long repairFixedDelay = 60000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getProcessingProbeAfterSeconds() {
            return processingProbeAfterSeconds;
        }

        public void setProcessingProbeAfterSeconds(long processingProbeAfterSeconds) {
            this.processingProbeAfterSeconds = processingProbeAfterSeconds;
        }

        public long getSafeReleaseAfterSeconds() {
            return safeReleaseAfterSeconds;
        }

        public void setSafeReleaseAfterSeconds(long safeReleaseAfterSeconds) {
            this.safeReleaseAfterSeconds = safeReleaseAfterSeconds;
        }

        public int getRepairBatchSize() {
            return repairBatchSize;
        }

        public void setRepairBatchSize(int repairBatchSize) {
            this.repairBatchSize = repairBatchSize;
        }

        public long getRepairFixedDelay() {
            return repairFixedDelay;
        }

        public void setRepairFixedDelay(long repairFixedDelay) {
            this.repairFixedDelay = repairFixedDelay;
        }
    }

    public static class ResultRetry {

        private boolean enabled = false;
        private List<String> delays = new ArrayList<>(List.of("5s", "30s", "2m", "10m"));
        private int maxAttempts = 4;
        private long fixedDelay = 1000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<String> getDelays() {
            return delays;
        }

        public void setDelays(List<String> delays) {
            this.delays = delays == null ? new ArrayList<>() : delays;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public long getFixedDelay() {
            return fixedDelay;
        }

        public void setFixedDelay(long fixedDelay) {
            this.fixedDelay = fixedDelay;
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

    public static class Bucket {

        private boolean enabled = false;
        private int defaultBucketCount = 16;
        private boolean hotPathAggregateRead = false;
        private Routing routing = new Routing();
        private CenterLedger centerLedger = new CenterLedger();
        private Transfer transfer = new Transfer();
        private Reconcile reconcile = new Reconcile();
        private AutoTransfer autoTransfer = new AutoTransfer();
        private Availability availability = new Availability();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getDefaultBucketCount() {
            return defaultBucketCount;
        }

        public void setDefaultBucketCount(int defaultBucketCount) {
            this.defaultBucketCount = defaultBucketCount;
        }

        public boolean isHotPathAggregateRead() {
            return hotPathAggregateRead;
        }

        public void setHotPathAggregateRead(boolean hotPathAggregateRead) {
            this.hotPathAggregateRead = hotPathAggregateRead;
        }

        public Routing getRouting() {
            return routing;
        }

        public void setRouting(Routing routing) {
            this.routing = routing;
        }

        public CenterLedger getCenterLedger() {
            return centerLedger;
        }

        public void setCenterLedger(CenterLedger centerLedger) {
            this.centerLedger = centerLedger;
        }

        public Transfer getTransfer() {
            return transfer;
        }

        public void setTransfer(Transfer transfer) {
            this.transfer = transfer;
        }

        public Reconcile getReconcile() {
            return reconcile;
        }

        public void setReconcile(Reconcile reconcile) {
            this.reconcile = reconcile;
        }

        public AutoTransfer getAutoTransfer() {
            return autoTransfer;
        }

        public void setAutoTransfer(AutoTransfer autoTransfer) {
            this.autoTransfer = autoTransfer;
        }

        public Availability getAvailability() {
            return availability;
        }

        public void setAvailability(Availability availability) {
            this.availability = availability == null ? new Availability() : availability;
        }
    }

    public static class Routing {

        private int physicalShardCount = 1;
        private List<Long> bucketShardKeys = new ArrayList<>();

        public int getPhysicalShardCount() {
            return physicalShardCount;
        }

        public void setPhysicalShardCount(int physicalShardCount) {
            this.physicalShardCount = physicalShardCount;
        }

        public List<Long> getBucketShardKeys() {
            return bucketShardKeys;
        }

        public void setBucketShardKeys(List<Long> bucketShardKeys) {
            this.bucketShardKeys = bucketShardKeys == null ? new ArrayList<>() : bucketShardKeys;
        }
    }

    public static class CenterLedger {

        private boolean enabled = false;
        private long fixedDelay = 1000;
        private int batchSize = 100;

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

    public static class Transfer {

        private boolean enabled = false;
        private long lockWaitMillis = 20;
        private long lockLeaseMillis = 200;
        private int maxAttempts = 1;
        private int size = 8;
        private long requestFallbackMinIntervalMillis = 0;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getLockWaitMillis() {
            return lockWaitMillis;
        }

        public void setLockWaitMillis(long lockWaitMillis) {
            this.lockWaitMillis = lockWaitMillis;
        }

        public long getLockLeaseMillis() {
            return lockLeaseMillis;
        }

        public void setLockLeaseMillis(long lockLeaseMillis) {
            this.lockLeaseMillis = lockLeaseMillis;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public int getSize() {
            return size;
        }

        public void setSize(int size) {
            this.size = size;
        }

        public long getRequestFallbackMinIntervalMillis() {
            return requestFallbackMinIntervalMillis;
        }

        public void setRequestFallbackMinIntervalMillis(long requestFallbackMinIntervalMillis) {
            this.requestFallbackMinIntervalMillis = requestFallbackMinIntervalMillis;
        }
    }

    public static class Reconcile {

        private boolean enabled = false;
        private long fixedDelay = 60000;
        private int batchSize = 100;

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

    public static class Availability {

        private boolean enabled = false;
        private long exhaustedBucketTtlMillis = 500;
        private long flushDelayMillis = 200;
        private long fixedDelay = 100;
        private int batchSize = 100;
        private long lockWaitMillis = 20;
        private long lockLeaseMillis = 500;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getExhaustedBucketTtlMillis() {
            return exhaustedBucketTtlMillis;
        }

        public void setExhaustedBucketTtlMillis(long exhaustedBucketTtlMillis) {
            this.exhaustedBucketTtlMillis = exhaustedBucketTtlMillis;
        }

        public long getFlushDelayMillis() {
            return flushDelayMillis;
        }

        public void setFlushDelayMillis(long flushDelayMillis) {
            this.flushDelayMillis = flushDelayMillis;
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

        public long getLockWaitMillis() {
            return lockWaitMillis;
        }

        public void setLockWaitMillis(long lockWaitMillis) {
            this.lockWaitMillis = lockWaitMillis;
        }

        public long getLockLeaseMillis() {
            return lockLeaseMillis;
        }

        public void setLockLeaseMillis(long lockLeaseMillis) {
            this.lockLeaseMillis = lockLeaseMillis;
        }
    }

    public static class AutoTransfer {

        private boolean enabled = false;
        private long fixedDelay = 60000;
        private int batchSize = 100;
        private int maxPairsPerSku = 8;
        private int lowWatermark = 0;
        private int transferSize = 8;
        private int sourceReserveQuantity = 1;
        private long lockWaitMillis = 20;
        private long lockLeaseMillis = 500;

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

        public int getMaxPairsPerSku() {
            return maxPairsPerSku;
        }

        public void setMaxPairsPerSku(int maxPairsPerSku) {
            this.maxPairsPerSku = maxPairsPerSku;
        }

        public int getLowWatermark() {
            return lowWatermark;
        }

        public void setLowWatermark(int lowWatermark) {
            this.lowWatermark = lowWatermark;
        }

        public int getTransferSize() {
            return transferSize;
        }

        public void setTransferSize(int transferSize) {
            this.transferSize = transferSize;
        }

        public int getSourceReserveQuantity() {
            return sourceReserveQuantity;
        }

        public void setSourceReserveQuantity(int sourceReserveQuantity) {
            this.sourceReserveQuantity = sourceReserveQuantity;
        }

        public long getLockWaitMillis() {
            return lockWaitMillis;
        }

        public void setLockWaitMillis(long lockWaitMillis) {
            this.lockWaitMillis = lockWaitMillis;
        }

        public long getLockLeaseMillis() {
            return lockLeaseMillis;
        }

        public void setLockLeaseMillis(long lockLeaseMillis) {
            this.lockLeaseMillis = lockLeaseMillis;
        }
    }
}


