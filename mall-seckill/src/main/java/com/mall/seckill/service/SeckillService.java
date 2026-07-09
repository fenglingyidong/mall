package com.mall.seckill.service;

import com.mall.seckill.pojo.vo.SeckillActivityView;
import com.mall.seckill.pojo.vo.SeckillResult;
import com.mall.seckill.pojo.vo.SeckillSubmitResponse;

import java.util.List;

public interface SeckillService {

    List<SeckillActivityView> activities();

    SeckillSubmitResponse submit(Long activityId, Long skuId);

    SeckillSubmitResponse submit(Long activityId, Long skuId, String requestId);

    SeckillResult result(String requestId);
}
