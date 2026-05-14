package com.mall.coupon.service.impl;

import com.mall.coupon.mapper.CouponRepository;
import com.mall.coupon.pojo.vo.CouponView;
import com.mall.coupon.service.CouponService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CouponServiceImpl implements CouponService {

    private final CouponRepository repository;

    public CouponServiceImpl(CouponRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<CouponView> available(Long skuId, Long userId) {
        return repository.available(skuId);
    }
}
