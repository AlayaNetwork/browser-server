package com.platon.browser.task;

import com.alibaba.fastjson.JSON;
import com.platon.browser.bean.RestrictingBalance;
import com.platon.browser.client.PlatOnClient;
import com.platon.browser.client.SpecialApi;
import com.platon.browser.config.TaskConfig;
import com.platon.browser.dao.entity.InternalAddress;
import com.platon.browser.dao.entity.InternalAddressExample;
import com.platon.browser.dao.mapper.CustomInternalAddressMapper;
import com.platon.browser.dao.mapper.InternalAddressMapper;
import com.platon.browser.enums.InternalAddressType;
import com.platon.protocol.Web3j;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.*;

@Component
@Slf4j
public class BalanceUpdateTask {
    @Resource
    private InternalAddressMapper internalAddressMapper;
    @Resource
    private CustomInternalAddressMapper customInternalAddressMapper;
    @Resource
    private SpecialApi specialApi;
    @Resource
    private PlatOnClient platOnClient;
    @Resource
    private TaskConfig config;

    /**
     * 更新基金会账户余额
     */
    @Scheduled(cron = "${task.fundAddressCron}")
    public void updateFundAccount() {
        log.info("更新基金会地址余额 START...");
       updateBalance(InternalAddressType.FUND_ACCOUNT);
        log.info("更新基金会地址余额 END。");
    }

    /**
     * 更新内置合约账户余额
     */
    @Scheduled(cron = "${task.innerContractCron}")
    public void updateContractAccount() {
        log.info("更新内置合约地址余额 START...");
        updateBalance(InternalAddressType.OTHER);
        log.info("更新内置合约地址余额 END。");
    }

    private void updateBalance(InternalAddressType type){
        InternalAddressExample example = new InternalAddressExample();
        switch (type){
            case FUND_ACCOUNT:
                example.createCriteria().andTypeEqualTo(type.getCode());
                break;
            case OTHER:
                example.createCriteria().andTypeNotEqualTo(InternalAddressType.FUND_ACCOUNT.getCode());
                break;
        }
        example.setOrderByClause(" address LIMIT "+config.getMaxAddressCount());
        List<InternalAddress> addressList = internalAddressMapper.selectByExample(example);
        if(!addressList.isEmpty()) {
            updateBalance(addressList);
        }else{
            log.info("地址数为0,不做操作！");
        }
    }

    private void updateBalance(List<InternalAddress> addressList) {
        List<Map<String,InternalAddress>> batchList = new ArrayList<>();
        Map<String,InternalAddress> batch = new HashMap<>();
        batchList.add(batch);
        for (InternalAddress address : addressList) {
            if(batch.size()>=config.getMaxBatchSize()){
                // 如果当前批次大小达到批次大小，则新建一个批次
                batch = new HashMap<>();
                batchList.add(batch);
            }
            // <地址-内部地址> 映射
            batch.put(address.getAddress(),address);
        }
        log.info("地址总数{},分成{}批,每批最多{}个地址", addressList.size(),batchList.size(),config.getMaxBatchSize());

        // 按批次查询并更新余额
        batchList.forEach(addressMap->{
            try {
                Web3j web3j = platOnClient.getWeb3jWrapper().getWeb3j();
                Set<String> addressSet = addressMap.keySet();
                String addresses = String.join(";", addressSet);
                log.info("锁仓余额查询参数：{}", addresses);
                List<RestrictingBalance> balanceList = specialApi.getRestrictingBalance(web3j,addresses);
                log.debug("锁仓余额查询结果：{}", JSON.toJSONString(balanceList));
                // 设置余额
                balanceList.forEach(balance->{
                    InternalAddress address = addressMap.get(balance.getAccount());
                    address.setBalance(new BigDecimal(balance.getFreeBalance()));
                    address.setRestrictingBalance(new BigDecimal(balance.getLockBalance().subtract(balance.getPledgeBalance())));
                });

                // 同步更新，防止表锁争用导致的死锁
                synchronized (BalanceUpdateTask.class){
                    // 批量更新余额
                    customInternalAddressMapper.batchInsertOrUpdateSelective(
                        addressMap.values(),
                        InternalAddress.Column.excludes(InternalAddress.Column.updateTime)
                    );
                    log.info("地址余额批量更新成功！");
                }
            }catch (Exception e){
                log.error("地址余额批量更新失败！",e);
            }
        });
    }
}