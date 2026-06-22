package com.mall.product.service.impl;

import com.alibaba.otter.canal.client.CanalConnector;
import com.alibaba.otter.canal.client.CanalConnectors;
import com.alibaba.otter.canal.protocol.CanalEntry;
import com.alibaba.otter.canal.protocol.Message;
import com.google.protobuf.InvalidProtocolBufferException;
import com.mall.product.config.CanalProperties;
import com.mall.product.mapper.ProductRepository;
import com.mall.product.service.ProductService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@ConditionalOnProperty(prefix = "mall.canal", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CanalProductCacheInvalidationListener {

    private static final Logger log = LoggerFactory.getLogger(CanalProductCacheInvalidationListener.class);

    @Autowired
    private CanalProperties properties;
    @Autowired
    private ProductRepository repository;
    @Autowired
    private ProductService productService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread worker;

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        worker = new Thread(this::listenLoop, "canal-product-cache-listener");
        worker.setDaemon(true);
        worker.start();
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (worker != null) {
            worker.interrupt();
        }
    }

    private void listenLoop() {
        while (running.get()) {
            CanalConnector connector = CanalConnectors.newSingleConnector(
                    new InetSocketAddress(properties.getHost(), properties.getPort()),
                    properties.getDestination(),
                    properties.getUsername(),
                    properties.getPassword()
            );
            try {
                connector.connect();
                connector.subscribe(properties.getSubscribe());
                connector.rollback();
                log.info("Canal product cache listener connected, subscribe={}", properties.getSubscribe());
                consume(connector);
            } catch (Exception exception) {
                log.warn("Canal product cache listener disconnected: {}", exception.getMessage());
                sleep(properties.getRetrySleepMillis());
            } finally {
                try {
                    connector.disconnect();
                } catch (Exception ignored) {
                    // Ignore disconnect errors.
                }
            }
        }
    }

    private void consume(CanalConnector connector) {
        while (running.get()) {
            Message message = connector.getWithoutAck(properties.getBatchSize());
            long batchId = message.getId();
            List<CanalEntry.Entry> entries = message.getEntries();
            if (batchId == -1 || entries.isEmpty()) {
                sleep(properties.getIdleSleepMillis());
                continue;
            }
            try {
                Set<Long> skuIds = affectedSkuIds(entries);
                skuIds.forEach(productService::invalidate);
                connector.ack(batchId);
                if (!skuIds.isEmpty()) {
                    log.info("Canal invalidated product detail cache, skuIds={}", skuIds);
                }
            } catch (Exception exception) {
                connector.rollback(batchId);
                log.warn("Canal message handling failed, batchId={}, error={}", batchId, exception.getMessage());
                sleep(properties.getRetrySleepMillis());
            }
        }
    }

    private Set<Long> affectedSkuIds(List<CanalEntry.Entry> entries) throws InvalidProtocolBufferException {
        Set<Long> skuIds = new LinkedHashSet<>();
        for (CanalEntry.Entry entry : entries) {
            if (entry.getEntryType() != CanalEntry.EntryType.ROWDATA) {
                continue;
            }
            CanalEntry.RowChange rowChange = CanalEntry.RowChange.parseFrom(entry.getStoreValue());
            if (rowChange.getIsDdl()) {
                continue;
            }
            String tableName = entry.getHeader().getTableName().toLowerCase(Locale.ROOT);
            for (CanalEntry.RowData rowData : rowChange.getRowDatasList()) {
                collectSkuIds(tableName, rowData, skuIds);
            }
        }
        return skuIds;
    }

    private void collectSkuIds(String tableName, CanalEntry.RowData rowData, Set<Long> skuIds) {
        switch (tableName) {
            case "sku" -> addIfPresent(skuIds, columnValue(rowData, "id"));
            case "sku_stock" -> addIfPresent(skuIds, columnValue(rowData, "sku_id"));
            case "spu" -> addAll(skuIds, repository.skuIdsBySpu(columnValue(rowData, "id")));
            case "brand" -> addAll(skuIds, repository.skuIdsByBrand(columnValue(rowData, "id")));
            case "category" -> addAll(skuIds, repository.skuIdsByCategory(columnValue(rowData, "id")));
            default -> {
                // The Canal subscribe regex should filter other tables.
            }
        }
    }

    private Long columnValue(CanalEntry.RowData rowData, String columnName) {
        Long value = columnValue(rowData.getAfterColumnsList(), columnName);
        return value == null ? columnValue(rowData.getBeforeColumnsList(), columnName) : value;
    }

    private Long columnValue(List<CanalEntry.Column> columns, String columnName) {
        return columns.stream()
                .filter(column -> columnName.equalsIgnoreCase(column.getName()))
                .filter(column -> !column.getIsNull())
                .findFirst()
                .map(CanalEntry.Column::getValue)
                .filter(value -> !value.isBlank())
                .map(Long::valueOf)
                .orElse(null);
    }

    private void addAll(Set<Long> skuIds, List<Long> values) {
        if (values != null) {
            values.forEach(value -> addIfPresent(skuIds, value));
        }
    }

    private void addIfPresent(Set<Long> skuIds, Long skuId) {
        if (skuId != null) {
            skuIds.add(skuId);
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
