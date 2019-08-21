package com.platon.browser.engine.handler;

import com.platon.browser.dto.*;
import com.platon.browser.engine.BlockChain;
import com.platon.browser.engine.cache.NodeCache;
import com.platon.browser.engine.result.StakingExecuteResult;
import com.platon.browser.exception.NoSuchBeanException;
import com.platon.browser.exception.SettleEpochChangeException;
import com.platon.browser.utils.HexTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.web3j.platon.bean.Node;
import org.web3j.platon.contracts.NodeContract;
import org.web3j.protocol.core.DefaultBlockParameter;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 结算周期变更事件处理类
 * @Auther: Chendongming
 * @Date: 2019/8/17 20:09
 * @Description:
 */
@Component
public class NewSettleEpochHandler implements EventHandler {
    private static Logger logger = LoggerFactory.getLogger(NewSettleEpochHandler.class);
    private CustomTransaction tx;
    private NodeCache nodeCache;
    private StakingExecuteResult executeResult;
    private BlockChain bc;

    @Override
    public void handle(EventContext context) throws SettleEpochChangeException {
        tx = context.getTransaction();
        nodeCache = context.getNodeCache();
        executeResult = context.getExecuteResult();
        bc = context.getBlockChain();
        //stakingSettle();
        modifyDelegationInfoOnNewSettingEpoch();
        modifyUnDelegationInfoOnNewSettingEpoch();
    }


    //结算周期变更导致的委托数据的变更
    private void modifyDelegationInfoOnNewSettingEpoch () {
        //由于结算周期的变更，对所有的节点下的质押的委托更新
        //只需变更不为历史节点的委托数据(isHistory=NO(2))
        List<CustomDelegation> delegations = nodeCache.getDelegationByIsHistory(Collections.singletonList(CustomDelegation.YesNoEnum.NO));
        delegations.forEach(delegation->{
            //经过结算周期的变更，上个周期的犹豫期金额累加到锁定期的金额
            delegation.setDelegateLocked(new BigInteger(delegation.getDelegateLocked()).add(new BigInteger(delegation.getDelegateHas())).toString());
            //累加后的犹豫期金额至为0
            delegation.setDelegateHas("0");
            delegation.setDelegateReduction("0");
            //并判断经过一个结算周期后该委托的对应赎回是否全部完成，若完成则将委托设置为历史节点
            //判断条件委托的犹豫期金额 + 委托的锁定期金额 + 委托的赎回金额是否等于0
            if (new BigInteger(delegation.getDelegateHas()).add(new BigInteger(delegation.getDelegateLocked())).add(new BigInteger(delegation.getDelegateReduction())) == BigInteger.ZERO) {
                delegation.setIsHistory(CustomDelegation.YesNoEnum.YES.code);
            }
            //添加需要更新的委托的信息到委托更新列表
            executeResult.stageUpdateDelegation(delegation);
        });
    }

    //结算周期变更导致的委托赎回的变更
    private void modifyUnDelegationInfoOnNewSettingEpoch () {
        //由于结算周期的变更，对所有的节点下的质押的委托的委托赎回更新
        //更新赎回委托的锁定中的金额：赎回锁定金额，在一个结算周期后到账，修改锁定期金额
        List<CustomUnDelegation> unDelegations = nodeCache.getUnDelegationByStatus(Collections.singletonList(CustomUnDelegation.StatusEnum.EXITING));
        unDelegations.forEach(unDelegation -> {
            //更新赎回委托的锁定中的金额：赎回锁定金额，在一个结算周期后到账，修改锁定期金额
            unDelegation.setRedeemLocked("0");
            //当锁定期金额为零时，认为此笔赎回委托交易已经完成
            unDelegation.setStatus(CustomUnDelegation.StatusEnum.EXITED.code);
            //添加需要更新的赎回委托信息到赎回委托更新列表
            executeResult.stageUpdateUnDelegation(unDelegation);
        });
    }

    /**
     * 对上一结算周期的质押节点结算
     * 对所有候选中和退出中的节点进行结算
     */
    private void stakingSettle() throws SettleEpochChangeException {
        // 结算周期切换时对所有候选中和退出中状态的节点进行结算

        // 前一结算周期内每个验证人所获得的平均质押奖励
        // 计算结算周期每个验证人所获得的平均质押奖励：((前一增发周期末激励池账户余额/(每个增发周期内的结算周期数))/上一结算周期验证人数)*质押激励比例
        if(bc.getPreVerifier().size()==0){
            throw new SettleEpochChangeException("上一结算周期取到的验证人列表为空，无法执行质押结算操作！");
        }
        BigInteger preVerifierStakingReward = new BigInteger(bc.getSettleReward().divide(BigDecimal.valueOf(bc.getCurVerifier().size()),0,RoundingMode.FLOOR).toString());
        logger.debug("上一结算周期验证人平均质押奖励:{}",preVerifierStakingReward.longValue());

        List<CustomStaking> stakings = nodeCache.getStakingByStatus(Arrays.asList(CustomStaking.StatusEnum.CANDIDATE,CustomStaking.StatusEnum.EXITING));
        for(CustomStaking curStaking:stakings){
            // 调整金额状态
            BigInteger stakingLocked = new BigInteger(curStaking.getStakingLocked()).add(new BigInteger(curStaking.getStakingHas()));
            curStaking.setStakingLocked(stakingLocked.toString());
            curStaking.setStakingHas(BigInteger.ZERO.toString());
            if(bc.getCurSettingEpoch().longValue() > curStaking.getStakingReductionEpoch()){
                // 因为减持质押需要隔一个结算周期才会释放，所以当前周期必须要大于当前质押中的解质押发生的周期，即：
                // 假设结算周期是500
                // |--------|--------|--------|
                // 1        500     1000     1500
                // 结算周期(1~500)内的解质押会在结算周期(500~1000)结束的时候(第1000块)释放
                // 假设周期(1~500)内做了质押A，staking.getStakingReductionEpoch()的值为1，则：
                // 1、第500块结算周期事件触发进来此方法时，bc.getCurSettingEpoch().longValue()的值为1，是不会进入此代码块的
                // 2、第1000块结算周期事件触发进来此方法时，bc.getCurSettingEpoch().longValue()的值为2，是会进入此代码块的
                //
                // 当前结算周期轮数大于质押结算周期标识，则表明前一结算周期的解质押已释放
                curStaking.setStakingReduction("0");
            }
            BigInteger stakingReduction = new BigInteger(curStaking.getStakingReduction());
            if(stakingLocked.add(stakingReduction).compareTo(BigInteger.ZERO)==0){
                curStaking.setStatus(CustomStaking.StatusEnum.EXITED.code);
            }
            // 计算质押激励和年化率
            Node node = bc.getPreVerifier().get(curStaking.getNodeId());
            if(node!=null){
                // 质押记录所属节点在前一轮结算周期的验证人列表中，则对其执行结算操作
                // 累加质押奖励
                BigInteger stakingRewardValue = new BigInteger(curStaking.getStakingRewardValue()).add(preVerifierStakingReward);
                curStaking.setStakingRewardValue(stakingRewardValue.toString());

                CustomNode customNode;
                try {
                    customNode = nodeCache.getNode(curStaking.getNodeId());
                } catch (NoSuchBeanException e) {
                    throw new SettleEpochChangeException("获取节点错误:"+e.getMessage());
                }
                // 计算年化率：((前一结算周期内每个验证人所获得的平均质押奖励+前一结算周期出块奖励)/0.25)*365*100%
                /**
                 * 每4个结算周期计算一次年化率
                 * 收益: W1   W2    W3    W4
                 *   |-----|-----|-----|-----|
                 * 成本:C1   C2    C3    C4
                 *
                 * 年化率计算：
                 * W1 + W2 + W3 + W4
                 * ------------------ x 一个增发周期内的结算周期总数 x 100%
                 * C1 + C2 + C3 + C4
                 */
                try {
                    // 累加最近4条质押记录的【质押奖励+出块奖励】，以及累加【锁定质押金】
                    List<CustomStaking> latest4Stakings = customNode.getLatestXStakings(4);
                    if(latest4Stakings.size()==4){
                        // 每四个结算周期统计一次
                        BigDecimal totalReward = BigDecimal.ZERO, totalCost = BigDecimal.ZERO;
                        for (CustomStaking staking:latest4Stakings){
                            totalReward=totalReward.add(new BigDecimal(staking.getStakingRewardValue()))
                                    .add(new BigDecimal(staking.getBlockRewardValue()));
                            // TODO: 质押成本是否只需要累加锁定质押即可
                            totalCost=totalCost.add(new BigDecimal(staking.getStakingLocked()));
                        }
                        BigDecimal expectIncomeRate = totalReward.divide(totalCost,4,RoundingMode.FLOOR)
                                .multiply(BigDecimal.valueOf(bc.getSettleEpochCountPerIssueEpoch().longValue())) // x每个增发周期内的结算周期数
                                .multiply(BigDecimal.valueOf(100));
                        curStaking.setExpectedIncome(expectIncomeRate.toString());
                    }
                } catch (NoSuchBeanException e) {
                    throw new SettleEpochChangeException("结算周期切换计算年化率出错:"+e.getMessage());
                }
                // 结算状态设置为已结算
                curStaking.setIsSetting(CustomStaking.YesNoEnum.YES.code);

                // 更新节点的质押金累计字段
                customNode.setStatRewardValue(curStaking.getStakingRewardValue());
                // 将改动的内存暂存至待更新缓存
                executeResult.stageUpdateNode(customNode);
            }else{
                // 年化率设置为0
                curStaking.setExpectedIncome(BigInteger.ZERO.toString());
                // 结算状态设置为未结算
                curStaking.setIsSetting(CustomStaking.YesNoEnum.NO.code);
            }

            // 将改动的内存暂存至待更新缓存
            executeResult.stageUpdateStaking(curStaking);



        }
    }
}
