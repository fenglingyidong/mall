package com.mall.product.service;

import io.seata.rm.tcc.api.BusinessActionContext;
import io.seata.rm.tcc.api.BusinessActionContextParameter;
import io.seata.rm.tcc.api.LocalTCC;
import io.seata.rm.tcc.api.TwoPhaseBusinessAction;

@LocalTCC
public interface StockTccAction {

    @TwoPhaseBusinessAction(
            name = "productStockTccAction",
            commitMethod = "commit",
            rollbackMethod = "rollback",
            useTCCFence = true
    )
    boolean prepare(BusinessActionContext context,
                    @BusinessActionContextParameter(paramName = "itemsJson") String itemsJson);

    boolean commit(BusinessActionContext context);

    boolean rollback(BusinessActionContext context);
}
