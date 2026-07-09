package com.mall.seckill.service.impl;

public final class SeckillStockChangeLogStatus {

    public static final String NEW = "NEW";
    public static final String OUTBOXING = "OUTBOXING";
    public static final String OUTBOXED = "OUTBOXED";
    public static final String OUTBOX_FAILED = "OUTBOX_FAILED";
    public static final String LEDGER_PROCESSING = "LEDGER_PROCESSING";
    public static final String APPLIED = "APPLIED";

    private SeckillStockChangeLogStatus() {
    }
}
