package com.mall.seckill.controller;

import com.mall.common.api.ApiResponse;
import com.mall.seckill.pojo.vo.SeckillActivityView;
import com.mall.seckill.pojo.vo.SeckillResult;
import com.mall.seckill.pojo.vo.SeckillSubmitResponse;
import com.mall.seckill.service.SeckillService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class SeckillController {

    @Autowired
    private SeckillService seckillService;

    @GetMapping("/api/seckill/activities")
    public ApiResponse<List<SeckillActivityView>> activities() {
        return ApiResponse.success(seckillService.activities());
    }

    @PostMapping("/api/seckill/{activityId}/{skuId}")
    public ApiResponse<SeckillSubmitResponse> submit(@PathVariable Long activityId,
                                                     @PathVariable Long skuId,
                                                     @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return ApiResponse.success(seckillService.submit(activityId, skuId, requestId));
    }

    @GetMapping("/api/seckill/result/{requestId}")
    public ApiResponse<SeckillResult> result(@PathVariable String requestId) {
        return ApiResponse.success(seckillService.result(requestId));
    }
}
