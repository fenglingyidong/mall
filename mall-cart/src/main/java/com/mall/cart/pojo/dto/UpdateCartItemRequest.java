package com.mall.cart.pojo.dto;

import jakarta.validation.constraints.Min;

public record UpdateCartItemRequest(@Min(1) Integer quantity, Boolean checked) {
}


