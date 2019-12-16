package com.platon.browser.controller;

import com.platon.browser.common.BrowserConst;
import com.platon.browser.config.CommonMethod;
import com.platon.browser.enums.I18nEnum;
import com.platon.browser.enums.RetEnum;
import com.platon.browser.exception.BusinessException;
import com.platon.browser.exception.ResponseException;
import com.platon.browser.now.service.HomeService;
import com.platon.browser.req.home.QueryNavigationRequest;
import com.platon.browser.res.BaseResp;
import com.platon.browser.res.home.*;
import com.platon.browser.util.I18nUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.WebAsyncTask;

import javax.validation.Valid;

/**
 *  首页模块Contract。定义使用方法
 *  @file AppDocHomeController.java
 *  @description 
 *	@author zhangrj
 *  @data 2019年8月31日
 */
@RestController
public class AppDocHomeController implements AppDocHome {

	@Autowired
    private I18nUtil i18n;
	
	@Autowired
    private HomeService homeService;
	
	@Override
	public WebAsyncTask<BaseResp<QueryNavigationResp>> queryNavigation(@Valid QueryNavigationRequest req) {
     // 5s钟没返回，则认为超时  
        WebAsyncTask<BaseResp<QueryNavigationResp>> webAsyncTask = new WebAsyncTask<>(BrowserConst.WEB_TIME_OUT, () -> {
             try{
                 QueryNavigationResp queryNavigationResp = homeService.queryNavigation(req);
                 return BaseResp.build(RetEnum.RET_SUCCESS.getCode(),i18n.i(I18nEnum.SUCCESS),queryNavigationResp);
             }catch (BusinessException be){
                 throw new ResponseException(be.getErrorMessage());
             }
        });
        CommonMethod.onTimeOut(webAsyncTask);
        return webAsyncTask;  
	}

	@Override
	public WebAsyncTask<BaseResp<BlockStatisticNewResp>> blockStatisticNew() {
		// 5s钟没返回，则认为超时  
        WebAsyncTask<BaseResp<BlockStatisticNewResp>> webAsyncTask = new WebAsyncTask<>(BrowserConst.WEB_TIME_OUT, () -> {
            BlockStatisticNewResp blockStatisticNewResp = homeService.blockStatisticNew();
            return BaseResp.build(RetEnum.RET_SUCCESS.getCode(),i18n.i(I18nEnum.SUCCESS),blockStatisticNewResp);
        });
        CommonMethod.onTimeOut(webAsyncTask);
        return webAsyncTask;  
	}

	@Override
	public WebAsyncTask<BaseResp<ChainStatisticNewResp>> chainStatisticNew() {
		// 5s钟没返回，则认为超时  
        WebAsyncTask<BaseResp<ChainStatisticNewResp>> webAsyncTask = new WebAsyncTask<>(BrowserConst.WEB_TIME_OUT, () -> {
            ChainStatisticNewResp chainStatisticNewResp = homeService.chainStatisticNew();
            return BaseResp.build(RetEnum.RET_SUCCESS.getCode(),i18n.i(I18nEnum.SUCCESS),chainStatisticNewResp);
        });
        CommonMethod.onTimeOut(webAsyncTask);
        return webAsyncTask;  
	}

	@Override
	public WebAsyncTask<BaseResp<StakingListNewResp>> stakingListNew() {
		// 5s钟没返回，则认为超时  
        WebAsyncTask<BaseResp<StakingListNewResp>> webAsyncTask = new WebAsyncTask<>(BrowserConst.WEB_TIME_OUT, () -> {
            StakingListNewResp stakingListNewResp = homeService.stakingListNew();
            /**
             * 第一次返回都设为true
             */
            stakingListNewResp.setIsRefresh(true);
            return BaseResp.build(RetEnum.RET_SUCCESS.getCode(),i18n.i(I18nEnum.SUCCESS),stakingListNewResp);
        });
        CommonMethod.onTimeOut(webAsyncTask);
        return webAsyncTask;  
	
	}

}
