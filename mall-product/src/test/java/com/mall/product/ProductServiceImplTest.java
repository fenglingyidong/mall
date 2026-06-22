package com.mall.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.product.mapper.CouponClient;
import com.mall.product.mapper.ProductRepository;
import com.mall.product.mapper.ReviewClient;
import com.mall.product.pojo.vo.ProductSearchItem;
import com.mall.product.service.StockTccAction;
import com.mall.product.service.impl.ProductCache;
import com.mall.product.service.impl.ProductServiceImpl;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductServiceImplTest {

    @Test
    void searchCapsLimitAndDelegatesFilters() {
        ProductRepository repository = mock(ProductRepository.class);
        ProductSearchItem item = new ProductSearchItem(
                1001L,
                501L,
                "AirLite Black 42",
                "AirLite",
                "Stride",
                "Running",
                new BigDecimal("499.00"),
                38
        );
        when(repository.search("AirLite", 101L, "Stride", new BigDecimal("100"), new BigDecimal("500"), 50))
                .thenReturn(List.of(item));

        ProductServiceImpl service = new ProductServiceImpl(
                repository,
                mock(ProductCache.class),
                mock(ReviewClient.class),
                mock(CouponClient.class),
                Runnable::run,
                mock(StockTccAction.class),
                new ObjectMapper()
        );

        List<ProductSearchItem> result = service.search(
                "AirLite",
                101L,
                "Stride",
                new BigDecimal("100"),
                new BigDecimal("500"),
                99
        );

        assertThat(result).containsExactly(item);
        verify(repository).search("AirLite", 101L, "Stride", new BigDecimal("100"), new BigDecimal("500"), 50);
    }

    @Test
    void searchUsesDefaultLimitWhenMissing() {
        ProductRepository repository = mock(ProductRepository.class);
        when(repository.search(null, null, null, null, null, 10)).thenReturn(List.of());

        ProductServiceImpl service = new ProductServiceImpl(
                repository,
                mock(ProductCache.class),
                mock(ReviewClient.class),
                mock(CouponClient.class),
                directExecutor(),
                mock(StockTccAction.class),
                new ObjectMapper()
        );

        assertThat(service.search(null, null, null, null, null, null)).isEmpty();
        verify(repository).search(null, null, null, null, null, 10);
    }

    private static Executor directExecutor() {
        return Runnable::run;
    }
}
