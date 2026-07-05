package com.mall.seckill.config;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.mall.seckill.service.impl.SentinelSeckillGuard;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class SentinelFlowRuleConfig {

    private final SeckillProperties properties;

    public SentinelFlowRuleConfig(SeckillProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void loadRules() {
        List<FlowRule> rules = new ArrayList<>();
        FlowRule submitRule = new FlowRule(SentinelSeckillGuard.SUBMIT_RESOURCE);
        submitRule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        submitRule.setCount(properties.getPermitsPerSecond());
        rules.add(submitRule);

        SeckillProperties.Hotspot hotspot = properties.getHotspot();
        if (hotspot.isEnabled()) {
            FlowRule hotspotRule = new FlowRule(SentinelSeckillGuard.HOTSPOT_SUBMIT_RESOURCE);
            hotspotRule.setGrade(RuleConstant.FLOW_GRADE_QPS);
            hotspotRule.setCount(hotspot.getPermitsPerSecond());
            rules.add(hotspotRule);
        }

        FlowRuleManager.loadRules(rules);
    }
}
