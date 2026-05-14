package com.mall.seckill.service.impl;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.mall.common.exception.BusinessException;
import org.springframework.stereotype.Component;

@Component
public class SentinelSeckillGuard {

    public static final String SUBMIT_RESOURCE = "seckill-submit";

    public void checkSubmit() {
        Entry entry = null;
        try {
            entry = SphU.entry(SUBMIT_RESOURCE);
        } catch (BlockException exception) {
            throw new BusinessException(429, "Too many requests");
        } finally {
            if (entry != null) {
                entry.exit();
            }
        }
    }
}
