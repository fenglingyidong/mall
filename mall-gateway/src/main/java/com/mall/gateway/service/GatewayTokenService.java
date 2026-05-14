package com.mall.gateway.service;

import com.mall.gateway.pojo.vo.GatewayUser;

public interface GatewayTokenService {

    GatewayUser verify(String authorization);
}
