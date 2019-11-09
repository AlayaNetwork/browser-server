package com.platon.browser.complement.converter.restricting;

import com.platon.browser.complement.converter.BusinessParamConverter;
import com.platon.browser.complement.dao.param.restricting.RestrictingCreate;
import com.platon.browser.complement.dao.param.restricting.RestrictingItem;
import com.platon.browser.common.queue.collection.event.CollectionEvent;
import com.platon.browser.elasticsearch.dto.Transaction;
import com.platon.browser.param.RestrictingCreateParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * @description: 委托业务参数转换器
 * @author: chendongming@juzix.net
 * @create: 2019-11-04 17:58:27
 **/
@Slf4j
@Service
public class RestrictingCreateConverter extends BusinessParamConverter<RestrictingCreate> {
	
    @Override
    public RestrictingCreate convert(CollectionEvent event, Transaction tx) {
		// 失败的交易不分析业务数据
		if(Transaction.StatusEnum.FAILURE.getCode()==tx.getStatus()) return null;

		long startTime = System.currentTimeMillis();

    	RestrictingCreateParam txParam = tx.getTxParam(RestrictingCreateParam.class);
    	String account = txParam.getAccount();
    	
    	List<RestrictingItem> restrictingItems = txParam.getPlans().stream().map(plan -> { return RestrictingItem.builder()
				.address(account)
				.amount(plan.getAmount())
				.epoch(plan.getEpoch().longValue())
				.number(BigInteger.valueOf(tx.getNum()))
				.build();
			}).collect(Collectors.toList());
    	
    	RestrictingCreate businessParam= RestrictingCreate.builder()
    			.itemList(restrictingItems)
                .build();

		log.debug("处理耗时:{} ms",System.currentTimeMillis()-startTime);

        return businessParam;
    }
}