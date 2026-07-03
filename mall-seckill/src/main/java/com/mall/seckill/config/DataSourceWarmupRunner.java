package com.mall.seckill.config;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class DataSourceWarmupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSourceWarmupRunner.class);
    private static final int MAX_WARMUP_CONNECTIONS = 512;

    private final DataSource dataSource;
    private final SeckillProperties properties;

    public DataSourceWarmupRunner(DataSource dataSource, SeckillProperties properties) {
        this.dataSource = dataSource;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        SeckillProperties.LoadTest loadTest = properties.getLoadTest();
        if (!loadTest.isConnectionWarmupEnabled()) {
            return;
        }

        int warmupSize = Math.max(1, Math.min(loadTest.getConnectionWarmupSize(), MAX_WARMUP_CONNECTIONS));
        ExecutorService executor = Executors.newFixedThreadPool(warmupSize);
        CountDownLatch acquired = new CountDownLatch(warmupSize);
        CountDownLatch release = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>(warmupSize);
        long started = System.nanoTime();

        try {
            for (int i = 0; i < warmupSize; i++) {
                futures.add(executor.submit(() -> warmupConnection(acquired, release)));
            }
            if (!acquired.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out while prewarming datasource connections");
            }
            release.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            release.countDown();
            executor.shutdown();
            executor.awaitTermination(30, TimeUnit.SECONDS);
        }

        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
        log.info("Prewarmed {} datasource connections in {} ms", warmupSize, elapsedMillis);
    }

    private void warmupConnection(CountDownLatch acquired, CountDownLatch release) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("SELECT 1");
            acquired.countDown();
            release.await(30, TimeUnit.SECONDS);
        } catch (Exception ex) {
            acquired.countDown();
            throw new IllegalStateException("Failed to prewarm datasource connection", ex);
        }
    }
}
