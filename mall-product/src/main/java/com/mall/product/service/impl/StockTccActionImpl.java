package com.mall.product.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.common.exception.BusinessException;
import com.mall.product.mapper.ProductRepository;
import com.mall.product.pojo.dto.StockDeductRequest;
import com.mall.product.service.StockTccAction;
import io.seata.rm.tcc.api.BusinessActionContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
public class StockTccActionImpl implements StockTccAction {

    private static final TypeReference<List<StockDeductRequest.Item>> ITEMS_TYPE = new TypeReference<>() {
    };

    private final ProductRepository repository;
    private final ProductCache cache;
    private final ObjectMapper objectMapper;

    public StockTccActionImpl(ProductRepository repository,
                              ProductCache cache,
                              ObjectMapper objectMapper) {
        this.repository = repository;
        this.cache = cache;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean prepare(BusinessActionContext context, String itemsJson) {
        List<StockDeductRequest.Item> items = readItems(itemsJson);
        repository.reserve(items);
        invalidate(items);
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean commit(BusinessActionContext context) {
        List<StockDeductRequest.Item> items = contextItems(context);
        repository.confirmReserved(items);
        invalidate(items);
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean rollback(BusinessActionContext context) {
        List<StockDeductRequest.Item> items = contextItems(context);
        repository.cancelReserved(items);
        invalidate(items);
        return true;
    }

    private List<StockDeductRequest.Item> contextItems(BusinessActionContext context) {
        if (context == null || context.getActionContext("itemsJson") == null) {
            return List.of();
        }
        return readItems(String.valueOf(context.getActionContext("itemsJson")));
    }

    private List<StockDeductRequest.Item> readItems(String itemsJson) {
        try {
            return objectMapper.readValue(itemsJson, ITEMS_TYPE);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(500, "Stock TCC context parse failed");
        }
    }

    private void invalidate(List<StockDeductRequest.Item> items) {
        items.forEach(item -> cache.invalidate(item.skuId()));
    }
}
