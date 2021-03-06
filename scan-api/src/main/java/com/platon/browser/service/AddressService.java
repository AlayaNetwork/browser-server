package com.platon.browser.service;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.text.StrFormatter;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.platon.browser.bean.CustomAddressDetail;
import com.platon.browser.bean.RestrictingBalance;
import com.platon.browser.client.PlatOnClient;
import com.platon.browser.client.SpecialApi;
import com.platon.browser.config.BlockChainConfig;
import com.platon.browser.dao.entity.Address;
import com.platon.browser.dao.entity.NetworkStat;
import com.platon.browser.dao.entity.RpPlan;
import com.platon.browser.dao.entity.RpPlanExample;
import com.platon.browser.dao.mapper.AddressMapper;
import com.platon.browser.dao.mapper.CustomAddressMapper;
import com.platon.browser.dao.mapper.CustomRpPlanMapper;
import com.platon.browser.dao.mapper.RpPlanMapper;
import com.platon.browser.elasticsearch.dto.Block;
import com.platon.browser.enums.I18nEnum;
import com.platon.browser.enums.TokenTypeEnum;
import com.platon.browser.exception.BusinessException;
import com.platon.browser.request.address.QueryDetailRequest;
import com.platon.browser.request.address.QueryRPPlanDetailRequest;
import com.platon.browser.response.address.DetailsRPPlanResp;
import com.platon.browser.response.address.QueryDetailResp;
import com.platon.browser.response.address.QueryRPPlanDetailResp;
import com.platon.browser.service.elasticsearch.AbstractEsRepository;
import com.platon.browser.service.elasticsearch.EsBlockRepository;
import com.platon.browser.service.elasticsearch.EsErc20TxRepository;
import com.platon.browser.service.elasticsearch.EsErc721TxRepository;
import com.platon.browser.service.elasticsearch.bean.ESResult;
import com.platon.browser.service.elasticsearch.query.ESQueryBuilderConstructor;
import com.platon.browser.utils.ConvertUtil;
import com.platon.browser.utils.I18nUtil;
import com.platon.browser.v0152.enums.ErcTypeEnum;
import com.platon.contracts.ppos.RestrictingPlanContract;
import com.platon.contracts.ppos.dto.CallResponse;
import com.platon.contracts.ppos.dto.resp.RestrictingItem;
import com.platon.contracts.ppos.dto.resp.Reward;
import com.platon.protocol.core.DefaultBlockParameterName;
import org.apache.commons.lang3.StringUtils;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * ??????????????????????????????
 *
 * @author zhangrj
 * @file AddressServiceImpl.java
 * @description
 * @data 2019???8???31???
 */
@Service
public class AddressService {

    private final Logger logger = LoggerFactory.getLogger(AddressService.class);

    @Resource
    private CustomAddressMapper customAddressMapper;

    @Resource
    private AddressMapper addressMapper;

    @Resource
    private RpPlanMapper rpPlanMapper;

    @Resource
    private CustomRpPlanMapper customRpPlanMapper;

    @Resource
    private PlatOnClient platonClient;

    @Resource
    private I18nUtil i18n;

    @Resource
    private BlockChainConfig blockChainConfig;

    @Resource
    private EsBlockRepository ESBlockRepository;

    @Resource
    private SpecialApi specialApi;

    @Resource
    private StatisticCacheService statisticCacheService;

    @Resource
    private EsErc20TxRepository esErc20TxRepository;

    @Resource
    private EsErc721TxRepository esErc721TxRepository;


    /**
     * ??????????????????
     *
     * @param req
     * @return com.platon.browser.response.address.QueryDetailResp
     * @author huangyongpeng@matrixelements.com
     * @date 2021/4/15
     */
    public QueryDetailResp getDetails(QueryDetailRequest req) {
        QueryDetailResp resp = new QueryDetailResp();
        // ????????????0?????????????????????
        if (StrUtil.isNotBlank(req.getAddress()) && com.platon.browser.utils.AddressUtil.isAddrZero(req.getAddress())) {
            return resp;
        }
        /** ?????????????????????????????? */
        CustomAddressDetail item = customAddressMapper.findAddressDetail(req.getAddress());
        if (item != null) {
            if (TokenTypeEnum.ERC20.getType().equalsIgnoreCase(item.getTokenType())) {
                resp.setHasErc20(true);
            } else {
                resp.setHasErc721(true);
            }
            BeanUtils.copyProperties(item, resp);
            resp.setDelegateUnlock(item.getDelegateHes());
            /** ?????????????????????????????? */
            resp.setIsRestricting(0);
            resp.setStakingValue(item.getStakingValue().add(item.getDelegateValue()));
            resp.setIsDestroy(StringUtils.isBlank(item.getContractDestroyHash()) ? 0 : 1);
            resp.setContractCreateHash(item.getContractCreatehash());
            resp.setDestroyHash(item.getContractDestroyHash());
            resp.setContractName(ConvertUtil.captureName(item.getContractName()));
        }
        /** ?????????????????????????????????  */
        try {
            this.getAddressInfo(req, resp);
            updateErcTxQtyFromES(req.getAddress(), resp, esErc20TxRepository, ErcTypeEnum.ERC20);
            updateErcTxQtyFromES(req.getAddress(), resp, esErc721TxRepository, ErcTypeEnum.ERC721);
        } catch (Exception e) {
            logger.error("getBalance error", e);
            platonClient.updateCurrentWeb3jWrapper();
            try {
                this.getAddressInfo(req, resp);
            } catch (Exception e1) {
                logger.error("getBalance error again", e);
            }
        }
        RpPlanExample rpPlanExample = new RpPlanExample();
        RpPlanExample.Criteria criteria = rpPlanExample.createCriteria();
        criteria.andAddressEqualTo(req.getAddress());
        List<RpPlan> rpPlans = rpPlanMapper.selectByExample(rpPlanExample);
        /** ????????????????????????????????????1 */
        if (rpPlans != null && !rpPlans.isEmpty()) {
            resp.setIsRestricting(1);
        }
        return resp;
    }

    /**
     * ???ES???????????????erc20/erc721???????????????????????????????????????????????????
     *
     * @param address
     * @param item
     * @param esRepository
     * @param ercTypeEnum
     * @return void
     * @author huangyongpeng@matrixelements.com
     * @date 2021/4/15
     */
    private void updateErcTxQtyFromES(String address, QueryDetailResp item, AbstractEsRepository esRepository, ErcTypeEnum ercTypeEnum) {
        try {
            ESQueryBuilderConstructor count = new ESQueryBuilderConstructor();
            count.buildMust(new BoolQueryBuilder()
                    .should(QueryBuilders.termQuery("from", address))
                    .should(QueryBuilders.termQuery("to", address)));
            ESResult<?> res = esRepository.Count(count);
            if (ErcTypeEnum.ERC20.equals(ercTypeEnum)) {
                if (ObjectUtil.isNull(item.getErc20TxQty()) || item.getErc20TxQty().compareTo(res.getTotal().intValue()) != 0) {
                    int oldErc20TxQty = Convert.toInt(item.getErc20TxQty(), 0);
                    int newErc20TxQty = Convert.toInt(res.getTotal(), 0);
                    item.setErc20TxQty(newErc20TxQty);
                    logger.info("??????{}???erc20?????????,??????{},??????{}??????", address, oldErc20TxQty, newErc20TxQty);
                }
            } else if (ErcTypeEnum.ERC721.equals(ercTypeEnum)) {
                if (ObjectUtil.isNull(item.getErc721TxQty()) || item.getErc721TxQty().compareTo(res.getTotal().intValue()) != 0) {
                    int oldErc721TxQty = Convert.toInt(item.getErc721TxQty(), 0);
                    int newErc721TxQty = Convert.toInt(res.getTotal(), 0);
                    item.setErc721TxQty(newErc721TxQty);
                    logger.info("??????{}???erc721?????????,??????{},??????{}??????", address, oldErc721TxQty, newErc721TxQty);
                }
            }
        } catch (Exception e) {
            logger.error(StrFormatter.format("????????????{}???erc???????????????", address), e);
        }
    }

    public QueryRPPlanDetailResp rpplanDetail(QueryRPPlanDetailRequest req) {
        QueryRPPlanDetailResp queryRPPlanDetailResp = new QueryRPPlanDetailResp();
        try {
            // ??????????????????????????????????????????
            List<RestrictingBalance> restrictingBalances = specialApi.getRestrictingBalance(platonClient.getWeb3jWrapper().getWeb3j(), req.getAddress());
            if (restrictingBalances != null && !restrictingBalances.isEmpty()) {
                /**
                 * ???????????????balance??????????????????
                 */
                queryRPPlanDetailResp.setRestrictingBalance(new BigDecimal(restrictingBalances.get(0).getLockBalance().subtract(restrictingBalances.get(0).getPledgeBalance())));
            }
            // ????????????????????????????????????????????????
            RestrictingPlanContract restrictingPlanContract = platonClient.getRestrictingPlanContract();
            CallResponse<RestrictingItem> baseResponse = restrictingPlanContract.getRestrictingInfo(req.getAddress()).send();
            if (baseResponse.isStatusOk()) {
                queryRPPlanDetailResp.setStakingValue(new BigDecimal(baseResponse.getData().getPledge()));
                queryRPPlanDetailResp.setUnderReleaseValue(new BigDecimal(baseResponse.getData().getDebt()));
            }
        } catch (Exception e) {
            logger.error("rpplanDetail error", e);
            throw new BusinessException(i18n.i(I18nEnum.SYSTEM_EXCEPTION));
        }
        /**
         * ?????????????????????????????????
         */
        RpPlanExample rpPlanExample = new RpPlanExample();
        RpPlanExample.Criteria criteria = rpPlanExample.createCriteria();
        criteria.andAddressEqualTo(req.getAddress());
        List<DetailsRPPlanResp> detailsRPPlanResps = new ArrayList<>();
        PageHelper.startPage(req.getPageNo(), req.getPageSize());
        Page<RpPlan> rpPlans = rpPlanMapper.selectByExample(rpPlanExample);
        for (RpPlan rPlan : rpPlans) {
            DetailsRPPlanResp detailsRPPlanResp = new DetailsRPPlanResp();
            BeanUtils.copyProperties(rPlan, detailsRPPlanResp);
            /**
             * ????????????????????????  ??????????????? * epoch  + number,?????????????????????????????????????????? * ???epoch-1???  + ???????????????
             */
            BigInteger number;
            long remainder = rPlan.getNumber() % blockChainConfig.getSettlePeriodBlockCount().longValue();
            if (remainder == 0l) {
                number = blockChainConfig.getSettlePeriodBlockCount()
                        .multiply(rPlan.getEpoch()).add(BigInteger.valueOf(rPlan.getNumber()));
            } else {
                number = blockChainConfig.getSettlePeriodBlockCount()
                        .multiply(rPlan.getEpoch().subtract(BigInteger.ONE)).add(BigInteger.valueOf(rPlan.getNumber()))
                        .add(blockChainConfig.getSettlePeriodBlockCount().subtract(BigInteger.valueOf(remainder)));
            }

            detailsRPPlanResp.setBlockNumber(number.toString());
            /** ???????????????????????????????????????????????????????????????????????????????????? */
            Block block = null;
            try {
                block = ESBlockRepository.get(String.valueOf(rPlan.getNumber()), Block.class);
            } catch (IOException e) {
                logger.error("?????????????????????", e);
            }
            BigDecimal diff = new BigDecimal(number.subtract(BigInteger.valueOf(rPlan.getNumber())));
            if (block != null) {
                if (diff.compareTo(BigDecimal.ZERO) > 0) {
                    NetworkStat networkStat = statisticCacheService.getNetworkStatCache();
                    detailsRPPlanResp.setEstimateTime(new BigDecimal(networkStat.getAvgPackTime()).multiply(diff)
                            .add(BigDecimal.valueOf(block.getTime().getTime())).longValue());
                } else {
                    detailsRPPlanResp.setEstimateTime(block.getTime().getTime());
                }
            }
            detailsRPPlanResps.add(detailsRPPlanResp);
        }
        queryRPPlanDetailResp.setRpPlans(detailsRPPlanResps);
        /**
         * ??????????????????
         */
        BigDecimal bigDecimal = customRpPlanMapper.selectSumByAddress(req.getAddress());
        if (bigDecimal != null) {
            queryRPPlanDetailResp.setTotalValue(bigDecimal);
        }
        /**
         * ??????????????????
         */
        queryRPPlanDetailResp.setTotal(rpPlans.getTotal());
        return queryRPPlanDetailResp;
    }

    private QueryDetailResp getAddressInfo(QueryDetailRequest req, QueryDetailResp resp) throws Exception {
        List<RestrictingBalance> restrictingBalances = specialApi.getRestrictingBalance(platonClient.getWeb3jWrapper().getWeb3j(), req.getAddress());
        if (restrictingBalances != null && !restrictingBalances.isEmpty()) {
            resp.setBalance(new BigDecimal(restrictingBalances.get(0).getFreeBalance()));
            resp.setRestrictingBalance(new BigDecimal(restrictingBalances.get(0).getLockBalance().subtract(restrictingBalances.get(0).getPledgeBalance())));
        }
        /** ?????????????????????????????????  */
        if (resp.getBalance().compareTo(BigDecimal.valueOf(10000000000l)) > 0) {
            BigInteger balance = platonClient.getWeb3jWrapper().getWeb3j().platonGetBalance(req.getAddress(), DefaultBlockParameterName.LATEST).send().getBalance();
            resp.setBalance(new BigDecimal(balance));
        }
        /**
         * ???????????????????????????????????????
         */
        List<String> nodes = new ArrayList<>();
        List<Reward> rewards = platonClient.getRewardContract().getDelegateReward(req.getAddress(), nodes).send().getData();
        /**
         * ????????????????????????return
         */
        if (rewards == null) {
            return resp;
        }
        BigDecimal allRewards = BigDecimal.ZERO;
        for (Reward reward : rewards) {
            allRewards = allRewards.add(new BigDecimal(reward.getReward()));
        }
        resp.setDelegateClaim(allRewards);
        return resp;
    }

}
