package com.platon.browser.message;

import com.alibaba.fastjson.JSON;
import com.maxmind.geoip2.model.CityResponse;
import com.maxmind.geoip2.record.Location;
import com.platon.browser.common.dto.agent.BlockDto;
import com.platon.browser.common.dto.agent.NodeDto;
import com.platon.browser.common.dto.agent.TransactionDto;
import com.platon.browser.common.dto.mq.Message;
import com.platon.browser.common.enums.MqMessageTypeEnum;
import com.platon.browser.config.ChainsConfig;
import com.platon.browser.dao.entity.Block;
import com.platon.browser.dto.IndexInfo;
import com.platon.browser.dto.RespPage;
import com.platon.browser.dto.StatisticInfo;
import com.platon.browser.dto.StatisticItem;
import com.platon.browser.dto.block.BlockItem;
import com.platon.browser.dto.node.NodeInfo;
import com.platon.browser.service.RedisCacheService;
import com.platon.browser.service.StompCacheService;
import com.platon.browser.util.GeoUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.*;

@Component
public class SubscribeService {

    private final Logger logger = LoggerFactory.getLogger(SubscribeService.class);

    // 记录每条链上已处理的最高块编号，防止重复处理
    private final Map<String,Long> highestBlockNumberMap = new HashMap<>();
    @Autowired
    private StompCacheService stompCacheService;
    @Autowired
    private ChainsConfig chainsConfig;
    @Autowired
    private RedisCacheService redisCacheService;

    @PostConstruct
    private void init(){
        // 从数据库加载最高块初始化每条链上的最高块编号标记
        chainsConfig.getChainIds().forEach(chainId->{
            logger.info("初始化链[{}]的最高块编号标记...",chainId);
            RespPage<BlockItem> page = redisCacheService.getBlockPage(chainId,1,1);
            long maxBlockNumber = 0;
            if(page.getData().size()>0){
                BlockItem block = page.getData().get(0);
                maxBlockNumber = block.getHeight();
            }
            highestBlockNumberMap.put(chainId,maxBlockNumber);
        });
    }

    @RabbitListener(queues = "#{platonQueue.name}")
    public void receive(String msg) {
        Message message = JSON.parseObject(msg,Message.class);
        String chainId = message.getChainId();
        if(!chainsConfig.isValid(chainId)){
            logger.error("[ID为{}]的链不受本系统监控！",message.getChainId());
            return;
        }

        switch (MqMessageTypeEnum.valueOf(message.getType().toUpperCase())){
            case NODE:
                logger.debug("[收到新节点消息]：{}",msg);
                logger.debug("  |- 更新节点缓存...");
                NodeDto nodeDto = JSON.parseObject(message.getStruct(), NodeDto.class);
                NodeInfo nodeInfo = new NodeInfo();
                BeanUtils.copyProperties(nodeDto,nodeInfo);
                CityResponse response = GeoUtil.getResponse(nodeDto.getIp());
                if(response!=null){
                    Location location = response.getLocation();
                    nodeInfo.setLatitude(location.getLatitude().floatValue());
                    nodeInfo.setLongitude(location.getLongitude().floatValue());
                }else{
                    // 默认设置为深圳的经纬度
                    nodeInfo.setLongitude(114.06667f);
                    nodeInfo.setLatitude(22.61667f);
                }
                List<NodeInfo> nodeInfoList = new ArrayList<>();
                nodeInfoList.add(nodeInfo);
                stompCacheService.updateNodeCache(nodeInfoList,false,chainId);

                logger.debug("  |- 更新指标信息中的共识节点个数...");
                IndexInfo indexInfo = new IndexInfo();
                indexInfo.setConsensusNodeAmount(0);
                nodeInfoList.forEach(node -> {
                    if(node.getNodeType()==1){
                        // 共识节点, 数量加一
                        indexInfo.setConsensusNodeAmount(indexInfo.getConsensusNodeAmount()+1);
                    }
                });
                stompCacheService.updateIndexCache(indexInfo,false,chainId);
                break;
            case BLOCK:
                logger.debug("[收到新区块消息]：{}",msg);
                logger.debug("  |- 更新区块缓存...");
                BlockDto blockDto = JSON.parseObject(message.getStruct(),BlockDto.class);
                long highestNumber = highestBlockNumberMap.get(chainId);
                if(blockDto.getNumber()<=highestNumber){
                    logger.error("消息中的块号低于当前链最高块号【当前链ID:{},最高块号:{},消息块号:{}】",chainId,highestNumber,blockDto.getNumber());
                    return;
                }

                // 更新redis中的区块列表缓存
                Block block = JSON.parseObject(message.getStruct(),Block.class);
                block.setChainId(chainId);
                //Date date = new Date();
                block.setTimestamp(new Date(blockDto.getTimestamp()));
                //block.setCreateTime(date);
                //block.setUpdateTime(date);
                Set<Block> blockSet = new HashSet<>();
                blockSet.add(block);
                redisCacheService.updateBlockCache(chainId,blockSet);

                logger.debug("  |- 更新指标信息中的当前块高和当前交易数...");
                indexInfo = new IndexInfo();
                indexInfo.setCurrentHeight(block.getNumber());
                indexInfo.setCurrentTransaction(blockDto.getTransaction().size());
                stompCacheService.updateIndexCache(indexInfo,false, chainId);

                logger.debug("  |- 更新交易缓存...");
                List<TransactionDto> transactionDtos = blockDto.getTransaction();

                // 对交易按交易索引正向排序
                Collections.sort(transactionDtos,(c1,c2)->{
                    long index1 = c1.getTransactionIndex().longValue();
                    long index2 = c2.getTransactionIndex().longValue();
                    if (index1>index2) return 1;
                    if (index1<index2) return -1;
                    return 0;
                });

                /*
                // 由于交易缓存排序缺少排序需要的唯一编号：需要由数据库为其产生sequence后再同步到redis缓存，才能做排序，所以更新redis缓存的
                // 动作移动到RedisCacheUpdateTask任务中进行
                Set<Transaction> transactionSet = new HashSet<>();
                if(transactionDtos.size()>0){
                    transactionDtos.forEach(transactionDto -> {
                        Transaction transaction = new Transaction();
                        BeanUtils.copyProperties(transactionDto,transaction);
                        transaction.setChainId(chainId);
                        transaction.setActualTxCost(transactionDto.getActualTxCoast().toString());
                        transaction.setBlockNumber(transactionDto.getBlockNumber().longValue());
                        transaction.setTimestamp(new Date(transactionDto.getTimestamp()));
                        transaction.setEnergonLimit(transactionDto.getEnergonLimit().toString());
                        transaction.setEnergonPrice(transactionDto.getEnergonPrice().toString());
                        transaction.setEnergonUsed(transactionDto.getEnergonUsed().toString());
                        transaction.setTransactionIndex(transactionDto.getTransactionIndex().intValue());

                        // 接收者状态：链上取回来的是十六进制字符串，需要转换为十进制整型
                        String txReceiptStatus = transactionDto.getTxReceiptStatus();
                        if(StringUtils.isNotBlank(txReceiptStatus)&&txReceiptStatus.startsWith("0x")&&txReceiptStatus.length()>2){
                            try{
                                int status = Integer.parseInt(txReceiptStatus.substring(2), 16);
                                transaction.setTxReceiptStatus(status>0?1:0);
                            }catch (Exception e){
                                transaction.setTxReceiptStatus(0);
                            }
                        }else{
                            transaction.setTxReceiptStatus(0);
                        }
                        //Date date1 =new Date();
                        //transaction.setCreateTime(date1);
                        //transaction.setUpdateTime(date1);
                        transactionSet.add(transaction);
                    });
                    //stompCacheService.updateTransactionCache(transactionInfos,chainId);
                    // 更新redis中的交易列表缓存
                    redisCacheService.updateTransactionCache(chainId,transactionSet);
                }*/

                logger.debug("  |- 更新统计缓存...");
                StatisticInfo statisticInfo = new StatisticInfo();
                statisticInfo.setHighestBlockNumber(block.getNumber());
                statisticInfo.setHighestBlockTimestamp(block.getTimestamp().getTime());
                statisticInfo.setBlockCount(1l);
                statisticInfo.setDayTransaction(Long.valueOf(transactionDtos.size()));
                List<StatisticItem> statisticItems = new ArrayList<>();
                StatisticItem statisticItem = new StatisticItem();
                statisticItem.setHeight(block.getNumber());
                statisticItem.setTime(block.getTimestamp().getTime());
                statisticItem.setTransaction(Long.valueOf(transactionDtos.size()));
                statisticItems.add(statisticItem);
                statisticInfo.setBlockStatisticList(statisticItems);
                stompCacheService.updateStatisticCache(statisticInfo,false,chainId);

                // 更新最高块号
                highestBlockNumberMap.put(chainId,Long.valueOf(blockDto.getNumber()));
                break;
        }
    }
}
