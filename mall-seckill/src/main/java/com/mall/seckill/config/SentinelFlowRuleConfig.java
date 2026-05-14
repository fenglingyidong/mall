package com.mall.seckill.config;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.mall.seckill.service.impl.SentinelSeckillGuard;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SentinelFlowRuleConfig {

    private final SeckillProperties properties;

    public SentinelFlowRuleConfig(SeckillProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void loadRules() {
        FlowRule rule = new FlowRule(SentinelSeckillGuard.SUBMIT_RESOURCE);
        rule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        rule.setCount(properties.getPermitsPerSecond());
        FlowRuleManager.loadRules(List.of(rule));
    }
}
