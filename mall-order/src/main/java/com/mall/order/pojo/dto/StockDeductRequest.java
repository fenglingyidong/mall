package com.mall.order.pojo.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record StockDeductRequest(@Valid @NotEmpty List<Item> items) {

    public record Item(@NotNull @Min(1) Long skuId, @NotNull @Min(1) Integer quantity) {
    }
}


