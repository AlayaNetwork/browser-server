package com.platon.browser.service;

import com.platon.browser.dao.entity.*;
import com.platon.browser.dao.mapper.*;
import com.platon.browser.dto.*;
import com.platon.browser.engine.BlockChain;
import com.platon.browser.engine.cache.NodeCache;
import com.platon.browser.engine.result.*;
import org.checkerframework.checker.units.qual.A;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.util.*;

/**
 * User: dongqile
 * Date: 2019/8/13
 * Time: 21:25
 */
@Component
public class DbService {
    @Autowired
    private BlockMapper blockMapper;
    @Autowired
    private TransactionMapper transactionMapper;

    @Autowired
    private CustomNodeMapper customNodeMapper;
    @Autowired
    private CustomStakingMapper customStakingMapper;
    @Autowired
    private CustomDelegationMapper customDelegationMapper;
    @Autowired
    private CustomUnDelegationMapper customUnDelegationMapper;
    @Autowired
    private CustomNodeOptMapper customNodeOptMapper;
    @Autowired
    private CustomSlashMapper customSlashMapper;
    @Autowired
    private CustomProposalMapper customProposalMapper;
    @Autowired
    private CustomVoteMapper customVoteMapper;
    @Autowired
    private CustomAddressMapper customAddressMapper;
    @Autowired
    private CustomNetworkStatMapper customNetworkStatMapper;

    @Autowired
    private BlockCacheService blockCacheService;
    @Autowired
    private TransactionCacheService transactionCacheService;
    @Autowired
    private NetworkStatCacheService networkStatCacheService;


    @Transactional
    public void insertOrUpdate ( List <CustomBlock> basicData, BlockChainResult bizData ) throws Exception {
        List <Block> blocks = new ArrayList <>();
        List <TransactionWithBLOBs> transactions = new ArrayList <>();
        basicData.forEach(block -> {
            blocks.add(block);
            transactions.addAll(block.getTransactionList());
        });
        // 批量入库区块数据并更新redis缓存
        if (blocks.size() > 0) {
            blockMapper.batchInsert(blocks);
            blockCacheService.update(new HashSet <>(blocks));
        }
        // 批量入库交易数据并更新redis缓存
        if (transactions.size() > 0) {
            transactionMapper.batchInsert(transactions);
            transactionCacheService.update(new HashSet <>(transactions));
        }
        // 统计数据入库并更新redis缓存
        NetworkStatResult nsr = bizData.getNetworkStatResult();
        if (nsr.getUpdateNetworkStats().size() > 0) {
            customNetworkStatMapper.batchInsertOrUpdateSelective(nsr.getUpdateNetworkStats(), NetworkStat.Column.values());
            networkStatCacheService.update(nsr.getUpdateNetworkStats());
        }


        // 质押相关数据
        StakingExecuteResult ser = bizData.getStakingExecuteResult();
        // 提案相关数据
        ProposalExecuteResult per = bizData.getProposalExecuteResult();
        // 地址相关数据
        AddressExecuteResult aer = bizData.getAddressExecuteResult();

        /*****************************批量新增操作 START**************************/

        // ****************新增节点质押相关数据*******************
        //批量入库新增节点数据
        if (ser.getAddNodes().size() > 0)
            customNodeMapper.batchInsertOrUpdateSelective(ser.getAddNodes(), Node.Column.values());
        //批量入库新增质押数据
        if (ser.getAddStakings().size() > 0)
            customStakingMapper.batchInsertOrUpdateSelective(ser.getAddStakings(), Staking.Column.values());
        //批量入库新增委托数据
        if (ser.getAddDelegations().size() > 0)
            customDelegationMapper.batchInsertOrUpdateSelective(ser.getAddDelegations(), Delegation.Column.values());
        //批量入库新增解委托数据
        if (ser.getAddUnDelegations().size() > 0)
            customUnDelegationMapper.batchInsertOrUpdateSelective(ser.getAddUnDelegations(), UnDelegation.Column.values());
        //批量入库新增惩罚数据
        if (ser.getAddSlashs().size() > 0)
            customSlashMapper.batchInsertOrUpdateSelective(ser.getAddSlashs(), Slash.Column.values());
        //批量入库新增操作数据
        if (ser.getAddNodeOpts().size() > 0)
            customNodeOptMapper.batchInsertOrUpdateSelective(ser.getAddNodeOpts(), NodeOpt.Column.values());

        // ****************新增提案相关数据*******************
        //批量入库新增治理数据
        if (per.getAddProposals().size() > 0)
            customProposalMapper.batchInsertOrUpdateSelective(per.getAddProposals(), Proposal.Column.values());
        //批量入库新增投票数据
        if (per.getAddVotes().size() > 0)
            customVoteMapper.batchInsertOrUpdateSelective(per.getAddVotes(), Vote.Column.values());

        /*****************************批量新增操作 END**************************/


        /*****************************批量更新操作 START**************************/
        // ****************更新质押相关数据*******************
        //批量入库或更新节点数据
        if (ser.getUpdateNodes().size() > 0)
            customNodeMapper.batchInsertOrUpdateSelective(ser.getUpdateNodes(), Node.Column.values());
        //批量入库更新质押数据
        if (ser.getUpdateStakings().size() > 0)
            customStakingMapper.batchInsertOrUpdateSelective(ser.getUpdateStakings(), Staking.Column.values());
        //批量入库更新委托数据
        if (ser.getUpdateDelegations().size() > 0)
            customDelegationMapper.batchInsertOrUpdateSelective(ser.getUpdateDelegations(), Delegation.Column.values());
        //批量入库更新解委托数据
        if (ser.getUpdateUnDelegations().size() > 0)
            customUnDelegationMapper.batchInsertOrUpdateSelective(ser.getUpdateUnDelegations(), UnDelegation.Column.values());

        // ****************更新提案相关数据*******************
        //批量更新提案
        if (per.getUpdateProposals().size() > 0)
            customProposalMapper.batchInsertOrUpdateSelective(per.getUpdateProposals(), Proposal.Column.values());

        // ****************批量插入或更新地址相关数据*******************
        Set <Address> addresses = aer.export();
        if (addresses.size() > 0) {
            customAddressMapper.batchInsertOrUpdateSelective(addresses, Address.Column.values());
        }
        /*****************************批量更新操作 END**************************/


    }


    public void dataOfStakingStatistics () {

        /**
         *  1.补充统计质押相关数据
         *      a.stat_delegate_has  关联的委托记录中犹豫期金额汇总
         *      b.stat_delegate_locked  关联的委托记录中锁定期金额汇总
         *      c.stat_delegate_reduction   关联的委托记录中退回中金额汇总
         *      d.stat_delegate_qty  关联的委托地址数
         */
        BlockChain.NODE_CACHE.getAll().values().forEach(node -> {
            for (Map.Entry <Long, CustomStaking> customStakingMap : node.getStakings().entrySet()) {
                //只统计不为历史的委托数据
                BigInteger statDelegateHas = BigInteger.ZERO;
                BigInteger statDelegateLocked = BigInteger.ZERO;
                BigInteger statDelegateReduction = BigInteger.ZERO;
                BigInteger statDelegateQty = BigInteger.ZERO;
                for (Map.Entry <String, CustomDelegation> customDelegationMap : customStakingMap.getValue().getDelegations().entrySet()) {
                    if (customDelegationMap.getValue().getIsHistory().equals(CustomDelegation.YesNoEnum.NO)) {
                        statDelegateHas.add(new BigInteger(customDelegationMap.getValue().getDelegateHas()));
                        statDelegateLocked.add(new BigInteger(customDelegationMap.getValue().getDelegateLocked()));
                        statDelegateReduction.add(new BigInteger(customDelegationMap.getValue().getDelegateReduction()));
                        statDelegateQty.add(BigInteger.ONE);
                    }
                }
                customStakingMap.getValue().setStatDelegateHas(statDelegateHas.toString());
                customStakingMap.getValue().setStatDelegateLocked(statDelegateLocked.toString());
                customStakingMap.getValue().setStatDelegateReduction(statDelegateReduction.toString());
                customStakingMap.getValue().setStatDelegateQty(statDelegateQty.intValue());
            }
        });
    }

    public void dataOfAddressStatistics () {
        /**
         * 2.补充统计地址相关数据
         *      a.staking_value  质押的金额
         *      b.delegate_value  委托的金额
         *      c.redeemed_value   赎回中的金额，包含委托和质押
         *      d.candidate_count   已委托的验证人
         *      e.delegate_hes   未锁定委托
         *      f.delegate_locked   已锁定委托
         *      g.delegate_unlock  已经解锁的 ——
         *      h.delegate_reduction  赎回中的
         *
         */

        for (Map.Entry <String, CustomAddress> customAddress : BlockChain.ADDRESS_CACHE.getAll().entrySet()) {
            BigInteger stakingValue = BigInteger.ZERO;
            BigInteger delegateValue = BigInteger.ZERO;
            BigInteger redeemedValue = BigInteger.ZERO;
            BigInteger candidateCount = BigInteger.ZERO;
            BigInteger delegateHes = BigInteger.ZERO;
            BigInteger delegateLocked = BigInteger.ZERO;
            BigInteger delegateUnlock = BigInteger.ZERO;
            BigInteger delegateReduction = BigInteger.ZERO;
            for (Map.Entry <String, CustomNode> customNodeEntry : BlockChain.NODE_CACHE.getAll().entrySet()) {
                for (Map.Entry <Long, CustomStaking> customStakingMap : customNodeEntry.getValue().getStakings().entrySet()) {
                    if (customStakingMap.getValue().getStakingAddr().equals(customAddress.getValue().getAddress())) {
                        stakingValue = new BigInteger(customStakingMap.getValue().getStakingHas()).add(new BigInteger(customStakingMap.getValue().getStakingLocked()));
                    }
                    for (Map.Entry <String, CustomDelegation> customDelegationMap : customStakingMap.getValue().getDelegations().entrySet()) {
                        if(customDelegationMap.getValue().getDelegateAddr().equals(customAddress.getValue().getAddress()) &&
                        customDelegationMap.getValue().getIsHistory().equals(CustomDelegation.YesNoEnum.NO)){
                            delegateValue = new BigInteger(customDelegationMap.getValue().getDelegateHas()).add(new BigInteger(customDelegationMap.getValue().getDelegateLocked()));


                        }
                    }
                }
            }
            customAddress.getValue().setStakingValue(stakingValue.toString());
            customAddress.getValue().setDelegateValue(delegateValue.toString());

        }
    }


}
