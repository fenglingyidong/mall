package com.mall.order.pojo.dto;

import jakarta.validation.constraints.Size;

public record CreateOrderRequest(@Size(max = 255) String remark) {
}


