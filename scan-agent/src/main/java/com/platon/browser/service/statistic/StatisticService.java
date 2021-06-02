package com.platon.browser.service.statistic;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.platon.browser.analyzer.statistic.StatisticsAddressAnalyzer;
import com.platon.browser.analyzer.statistic.StatisticsNetworkAnalyzer;
import com.platon.browser.bean.CollectionEvent;
import com.platon.browser.bean.EpochMessage;
import com.platon.browser.bean.NodeSettleStatis;
import com.platon.browser.bean.NodeSettleStatisBase;
import com.platon.browser.cache.AddressCache;
import com.platon.browser.dao.entity.Address;
import com.platon.browser.dao.entity.Node;
import com.platon.browser.dao.mapper.CustomNodeMapper;
import com.platon.browser.dao.mapper.NodeMapper;
import com.platon.browser.elasticsearch.dto.Block;
import com.platon.browser.exception.NoSuchBeanException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.math.BigInteger;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 统计入库参数服务
 *
 * @author chendai
 */
@Slf4j
@Service
public class StatisticService {

    @Resource
    private AddressCache addressCache;

    @Resource
    private StatisticsNetworkAnalyzer statisticsNetworkAnalyzer;

    @Resource
    private StatisticsAddressAnalyzer statisticsAddressAnalyzer;

    @Resource
    private NodeMapper nodeMapper;

    @Resource
    private CustomNodeMapper customNodeMapper;

    /**
     * 解析区块, 构造业务入库参数信息
     *
     * @return
     */
    public void analyze(CollectionEvent event) throws NoSuchBeanException {
        long startTime = System.currentTimeMillis();
        Block block = event.getBlock();
        EpochMessage epochMessage = event.getEpochMessage();
        // 地址统计
        Collection<Address> addressList = this.addressCache.getAll();
        if (block.getNum() == 0) {
            if (CollUtil.isNotEmpty(addressList)) {
                // 初始化内置地址，比如内置合约等
                this.statisticsAddressAnalyzer.analyze(event, block, epochMessage);
            }
            return;
        }
        this.statisticsNetworkAnalyzer.analyze(event, block, epochMessage);
        // 程序逻辑运行至此处，所有ppos相关业务逻辑已经分析完成，进行地址入库操作
        if (!addressList.isEmpty()) {
            this.statisticsAddressAnalyzer.analyze(event, block, epochMessage);
        }
        log.debug("处理耗时:{} ms", System.currentTimeMillis() - startTime);
    }

    /**
     * 节点结算周期的出块统计---节点当选出块节点次数
     *
     * @param event
     * @return void
     * @date 2021/6/2
     */
    public void nodeSettleStatisElected(CollectionEvent event) {
        try {
            // 为所有的节点的当选节点次数初始化
            List<Node> nodeList = nodeMapper.selectByExample(null);
            List<Node> updateNodeList = CollUtil.newArrayList();
            if (CollUtil.isNotEmpty(nodeList)) {
                nodeList.forEach(node -> {
                    String info = node.getNodeSettleStatisInfo();
                    NodeSettleStatis nodeSettleStatis;
                    if (StrUtil.isEmpty(info)) {
                        nodeSettleStatis = new NodeSettleStatis();
                        nodeSettleStatis.setNodeId(node.getNodeId());
                        nodeSettleStatis.setBlockNum(0L);
                        NodeSettleStatisBase nodeSettleStatisBase = new NodeSettleStatisBase();
                        nodeSettleStatisBase.setSettleEpochRound(event.getEpochMessage().getSettleEpochRound());
                        nodeSettleStatisBase.setBlockNumGrandTotal(BigInteger.ZERO);
                        if (inCurValidator(event.getEpochMessage().getCurValidatorList(), event.getBlock().getNodeId())) {
                            nodeSettleStatisBase.setBlockNumElected(BigInteger.ONE);
                        } else {
                            nodeSettleStatisBase.setBlockNumElected(BigInteger.ZERO);
                        }
                        nodeSettleStatis.getNodeSettleStatisQueue().add(nodeSettleStatisBase);
                    } else {
                        nodeSettleStatis = NodeSettleStatis.jsonToBean(info);
                        if (event.getEpochMessage().getCurrentBlockNumber().compareTo(BigInteger.valueOf(nodeSettleStatis.getBlockNum())) > 0) {
                            addNodeSettleStatisElected(event.getEpochMessage().getCurValidatorList(), event.getBlock().getNodeId(), event.getEpochMessage().getSettleEpochRound(), nodeSettleStatis);
                        }
                    }
                    Node updateNode = new Node();
                    updateNode.setNodeId(node.getNodeId());
                    updateNode.setNodeSettleStatisInfo(JSONUtil.toJsonStr(nodeSettleStatis));
                    updateNodeList.add(updateNode);
                });
                int res = customNodeMapper.updateNodeSettleStatis(updateNodeList);
                if (res > 0) {
                    log.info("节点列表在共识轮数[{}]块高[{}]当选出块节点[{}]更新成功", event.getEpochMessage().getConsensusEpochRound(), event.getEpochMessage().getCurrentBlockNumber(), JSONUtil.toJsonStr(updateNodeList));
                } else {
                    log.error("节点列表在共识轮数[{}]块高[{}]当选出块节点[{}]更新失败", event.getEpochMessage().getConsensusEpochRound(), event.getEpochMessage().getCurrentBlockNumber(), JSONUtil.toJsonStr(updateNodeList));
                }
            }
        } catch (Exception e) {
            log.error(StrUtil.format("节点在共识轮数[{}]块高[{}]当选出块节点更新异常", event.getEpochMessage().getConsensusEpochRound(), event.getEpochMessage().getCurrentBlockNumber()), e);
        }
    }

    /**
     * 添加节点区块数统计
     *
     * @param curValidatorList   当前共识周期验证人列表
     * @param nodeId             节点id
     * @param curIssueEpochRound 当前结算周期轮数
     * @param nodeSettleStatis   节点结算周期的出块统计
     * @return void
     * @date 2021/6/1
     */
    private void addNodeSettleStatisElected(List<com.platon.contracts.ppos.dto.resp.Node> curValidatorList, String nodeId, BigInteger curIssueEpochRound, NodeSettleStatis nodeSettleStatis) {
        if (nodeSettleStatis.getNodeSettleStatisQueue().size() > 0) {
            List<NodeSettleStatisBase> list = nodeSettleStatis.getNodeSettleStatisQueue().toList();
            // 已记录的最高结算周期轮数，队列已排序
            BigInteger recordSettleEpochRound = list.get(0).getSettleEpochRound();
            if (recordSettleEpochRound.compareTo(curIssueEpochRound) == 0) {
                if (inCurValidator(curValidatorList, nodeId)) {
                    BigInteger newBlockNumElected = list.get(0).getBlockNumElected().add(BigInteger.ONE);
                    list.get(0).setBlockNumElected(newBlockNumElected);
                }
                nodeSettleStatis.getNodeSettleStatisQueue().clear();
                nodeSettleStatis.getNodeSettleStatisQueue().addAll(list);
            } else {
                // 记录下一个结算周期轮数
                NodeSettleStatisBase nodeSettleStatisBase = new NodeSettleStatisBase();
                nodeSettleStatisBase.setSettleEpochRound(curIssueEpochRound);
                nodeSettleStatisBase.setBlockNumGrandTotal(BigInteger.ZERO);
                if (inCurValidator(curValidatorList, nodeId)) {
                    nodeSettleStatisBase.setBlockNumElected(BigInteger.ONE);
                } else {
                    nodeSettleStatisBase.setBlockNumElected(BigInteger.ZERO);
                }
                nodeSettleStatis.getNodeSettleStatisQueue().offer(nodeSettleStatisBase);
            }
        } else {
            log.error("节点[{}]统计数据[{}]异常，请校验", nodeId, JSONUtil.toJsonStr(nodeSettleStatis));
        }
    }


    /**
     * 节点结算周期的出块统计---累计出块数
     *
     * @param event
     * @return void
     * @date 2021/5/31
     */
    public void nodeSettleStatisBlockNum(CollectionEvent event) {
        try {
            Node node = nodeMapper.selectByPrimaryKey(event.getBlock().getNodeId());
            if (ObjectUtil.isNull(node)) {
                return;
            }
            String info = node.getNodeSettleStatisInfo();
            NodeSettleStatis nodeSettleStatis;
            if (StrUtil.isEmpty(info)) {
                nodeSettleStatis = new NodeSettleStatis();
                nodeSettleStatis.setNodeId(node.getNodeId());
                nodeSettleStatis.setBlockNum(event.getEpochMessage().getCurrentBlockNumber().longValue());
                NodeSettleStatisBase nodeSettleStatisBase = new NodeSettleStatisBase();
                nodeSettleStatisBase.setSettleEpochRound(event.getEpochMessage().getSettleEpochRound());
                nodeSettleStatisBase.setBlockNumGrandTotal(BigInteger.ONE);
                nodeSettleStatisBase.setBlockNumElected(BigInteger.ZERO);
                nodeSettleStatis.getNodeSettleStatisQueue().add(nodeSettleStatisBase);
            } else {
                nodeSettleStatis = NodeSettleStatis.jsonToBean(info);
                if (event.getEpochMessage().getCurrentBlockNumber().compareTo(BigInteger.valueOf(nodeSettleStatis.getBlockNum())) > 0) {
                    addNodeSettleStatisBlockNum(event.getEpochMessage().getCurrentBlockNumber().longValue(), event.getBlock().getNodeId(), event.getEpochMessage().getSettleEpochRound(), nodeSettleStatis);
                }
            }
            updateNodeSettleStatis(node.getNodeId(), JSONUtil.toJsonStr(nodeSettleStatis));
        } catch (Exception e) {
            log.error(StrUtil.format("节点[{}]结算周期的出块统计异常", event.getBlock().getNodeId()), e);
        }
    }

    /**
     * 添加节点区块数统计
     *
     * @param blockNum           块高
     * @param nodeId             节点id
     * @param curIssueEpochRound 当前结算周期轮数
     * @param nodeSettleStatis   节点结算周期的出块统计
     * @return void
     * @date 2021/6/1
     */
    private void addNodeSettleStatisBlockNum(Long blockNum, String nodeId, BigInteger curIssueEpochRound, NodeSettleStatis nodeSettleStatis) {
        nodeSettleStatis.setBlockNum(blockNum);
        if (nodeSettleStatis.getNodeSettleStatisQueue().size() > 0) {
            List<NodeSettleStatisBase> list = nodeSettleStatis.getNodeSettleStatisQueue().toList();
            // 已记录的最高结算周期轮数，队列已排序
            BigInteger recordSettleEpochRound = list.get(0).getSettleEpochRound();
            if (recordSettleEpochRound.compareTo(curIssueEpochRound) == 0) {
                BigInteger newBlockNumGrandTotal = list.get(0).getBlockNumGrandTotal().add(BigInteger.ONE);
                list.get(0).setBlockNumGrandTotal(newBlockNumGrandTotal);
                nodeSettleStatis.getNodeSettleStatisQueue().clear();
                nodeSettleStatis.getNodeSettleStatisQueue().addAll(list);
            } else {
                // 记录下一个结算周期轮数
                NodeSettleStatisBase nodeSettleStatisBase = new NodeSettleStatisBase();
                nodeSettleStatisBase.setSettleEpochRound(curIssueEpochRound);
                nodeSettleStatisBase.setBlockNumGrandTotal(BigInteger.ONE);
                nodeSettleStatisBase.setBlockNumElected(BigInteger.ZERO);
                nodeSettleStatis.getNodeSettleStatisQueue().offer(nodeSettleStatisBase);
            }
        } else {
            log.error("节点[{}]统计数据[{}]异常，请校验", nodeId, JSONUtil.toJsonStr(nodeSettleStatis));
        }
    }

    /**
     * 更新节点区块数统计信息
     *
     * @param nodeId
     * @param json
     * @return void
     * @date 2021/6/2
     */
    private void updateNodeSettleStatis(String nodeId, String json) {
        Node updateNode = new Node();
        updateNode.setNodeId(nodeId);
        updateNode.setNodeSettleStatisInfo(json);
        int res = nodeMapper.updateByPrimaryKeySelective(updateNode);
        if (res > 0) {
            log.info("节点在结算周期的出块统计信息[{}]更新成功", json);
        } else {
            log.error("节点在结算周期的出块统计信息[{}]更新失败", json);
        }
    }

    /**
     * 判断当前节点是否在当前共识周期验证人列表
     *
     * @param curValidatorList 当前共识周期验证人列表
     * @param nodeId           节点
     * @return boolean
     * @date 2021/5/31
     */
    private boolean inCurValidator(List<com.platon.contracts.ppos.dto.resp.Node> curValidatorList, String nodeId) {
        if (CollUtil.isNotEmpty(curValidatorList)) {
            List<com.platon.contracts.ppos.dto.resp.Node> curValidator = curValidatorList.stream().filter(v -> {
                return v.getNodeId().equalsIgnoreCase(nodeId);
            }).collect(Collectors.toList());
            if (curValidator.size() > 0) {
                return true;
            } else {
                return false;
            }
        } else {
            return false;
        }
    }

}
