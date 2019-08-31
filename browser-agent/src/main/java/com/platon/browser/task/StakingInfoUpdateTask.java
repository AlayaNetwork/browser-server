package com.platon.browser.task;

import com.alibaba.fastjson.JSON;
import com.platon.browser.dto.CustomStaking;
import com.platon.browser.engine.BlockChain;
import com.platon.browser.engine.bean.keystore.Completion;
import com.platon.browser.engine.bean.keystore.Components;
import com.platon.browser.engine.bean.keystore.KeyBaseUser;
import com.platon.browser.util.MarkDownParserUtil;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import static com.platon.browser.engine.BlockChain.NODE_CACHE;

/**
 * @Auther: dongqile
 * @Date: 2019/8/17 20:09
 * @Description: 质押信息更新任务
 */
@Component
public class StakingInfoUpdateTask {

    private static Logger logger = LoggerFactory.getLogger(StakingInfoUpdateTask.class);

    @Autowired
    private BlockChain blockChain;

    private static final String fingerprintpPer = "_/api/1.0/user/autocomplete.json?q=";


    @Scheduled(cron = "0/3  * * * * ?")
    protected void start () {
        String keyStoreUrl = blockChain.getChainConfig().getKeyBase();
        try {
            Set <CustomStaking> customStakingSet = NODE_CACHE.getAllStaking();
            if (customStakingSet.size() == 0) return;
            customStakingSet.forEach(customStaking -> {
                if (StringUtils.isNotBlank(customStaking.getExternalId())) {
                    String queryUrl = keyStoreUrl.concat(fingerprintpPer.concat(customStaking.getExternalId()));
                    try {
                        String queryResult = MarkDownParserUtil.httpGet(queryUrl);
                        KeyBaseUser keyBaseUser = JSON.parseObject(queryResult, KeyBaseUser.class);
                        List <Completion> completions = keyBaseUser.getCompletions();
                        if (completions == null || completions.size() == 0) return;
                        // 取最新一条
                        Completion completion = completions.get(0);
                        // 取缩略图
                        String icon = completion.getThumbnail();
                        customStaking.setStakingIcon(icon);

                        Components components = completion.getComponents();
                        String username = components.getUsername().getVal();
                        customStaking.setExternalName(username);

                    } catch (IOException e) {
                        logger.error("[StakingInfoUpdateTask] IOException {}", e.getMessage());
                    } catch (Exception e) {
                        logger.error("[StakingInfoUpdateTask] Exception {}", e.getMessage());
                    }
                }
            });
        } catch (Exception e) {
            logger.error("{}", e.getMessage());
        }
    }
}