package com.platon.browser.task;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.collection.ConcurrentHashSet;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.text.StrFormatter;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.ssl.TrustAnyHostnameVerifier;
import cn.hutool.json.JSONUtil;
import com.alibaba.fastjson.JSON;
import com.platon.browser.bean.CommonConstant;
import com.platon.browser.bean.TokenHolderCount;
import com.platon.browser.dao.entity.*;
import com.platon.browser.dao.mapper.*;
import com.platon.browser.elasticsearch.dto.ErcTx;
import com.platon.browser.service.elasticsearch.AbstractEsRepository;
import com.platon.browser.service.elasticsearch.EsErc20TxRepository;
import com.platon.browser.service.elasticsearch.EsErc721TxRepository;
import com.platon.browser.service.elasticsearch.bean.ESResult;
import com.platon.browser.service.elasticsearch.query.ESQueryBuilderConstructor;
import com.platon.browser.service.elasticsearch.query.ESQueryBuilders;
import com.platon.browser.service.erc.ErcServiceImpl;
import com.platon.browser.task.bean.TokenInventoryUpdate;
import com.platon.browser.utils.AddressUtil;
import com.platon.browser.utils.AppStatusUtil;
import com.platon.browser.utils.CommonUtil;
import com.platon.browser.v0152.analyzer.ErcCache;
import com.platon.browser.v0152.bean.ErcToken;
import com.platon.browser.v0152.enums.ErcTypeEnum;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.elasticsearch.index.query.QueryBuilders;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * token?????????
 * ?????????????????????????????????
 *
 * @author huangyongpeng@matrixelements.com
 * @date 2021/1/22
 */
@Slf4j
@Component
public class ErcTokenUpdateTask {

    @Resource
    private CustomTokenMapper customTokenMapper;

    @Resource
    private TokenHolderMapper tokenHolderMapper;

    @Resource
    private CustomTokenHolderMapper customTokenHolderMapper;

    @Resource
    private TokenInventoryMapper tokenInventoryMapper;

    @Resource
    private CustomTokenInventoryMapper customTokenInventoryMapper;

    @Resource
    private TokenMapper tokenMapper;

    @Resource
    private ErcCache ercCache;

    @Resource
    private ErcServiceImpl ercServiceImpl;

    @Resource
    private EsErc20TxRepository esErc20TxRepository;

    @Resource
    private EsErc721TxRepository esErc721TxRepository;

    private static final int TOKEN_BATCH_SIZE = 10;

    private static final ExecutorService TOKEN_UPDATE_POOL = Executors.newFixedThreadPool(TOKEN_BATCH_SIZE);

    private static final int HOLDER_BATCH_SIZE = 10;

    private static final ExecutorService HOLDER_UPDATE_POOL = Executors.newFixedThreadPool(HOLDER_BATCH_SIZE);

    private static final ExecutorService INCREMENT_HOLDER_UPDATE_POOL = Executors.newFixedThreadPool(HOLDER_BATCH_SIZE);

    private static final int INVENTORY_BATCH_SIZE = 10;

    private static final ExecutorService INVENTORY_UPDATE_POOL = Executors.newFixedThreadPool(INVENTORY_BATCH_SIZE);

    private static final ExecutorService INCREMENT_INVENTORY_UPDATE_POOL = Executors.newFixedThreadPool(INVENTORY_BATCH_SIZE);

    private final static OkHttpClient client = new OkHttpClient.Builder()
            .connectionPool(new ConnectionPool(50, 5, TimeUnit.MINUTES))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .hostnameVerifier(new TrustAnyHostnameVerifier())
            .build();

    /**
     * token???????????????
     */
    @Getter
    @Setter
    private volatile long tokenCreateTime = 0L;

    /**
     * tokenInventory???????????????
     */
    @Getter
    @Setter
    private volatile int tokenInventoryPage = 0;

    /**
     * tokenInventory???????????????????????????????????????
     */
    private volatile TokenInventoryUpdate tokenInventoryUpdate = new TokenInventoryUpdate(0, false, 0);

    /**
     * tokenInventory???????????????????????????????????????
     */
    private volatile TokenInventoryUpdate incrementTokenInventoryUpdate = new TokenInventoryUpdate(0, false, 0);

    /**
     * TokenHolderERC20????????????
     */
    @Getter
    @Setter
    private volatile Long erc20TxSeq = 0L;

    /**
     * TokenHolderERC721????????????
     */
    @Getter
    @Setter
    private volatile Long erc721TxSeq = 0L;

    private final Lock lock = new ReentrantLock();

    private final Lock tokenInventoryLock = new ReentrantLock();

    private final Lock tokenHolderLock = new ReentrantLock();

    /**
     * ??????ERC20???Erc721Enumeration token???????????????===???????????????
     * ???5????????????
     *
     * @return void
     * @author huangyongpeng@matrixelements.com
     * @date 2021/1/18
     */
    @Scheduled(cron = "0 */5 * * * ?")
    public void cronUpdateTokenTotalSupply() {
        lock.lock();
        try {
            MDC.put(CommonConstant.TRACE_ID, CommonUtil.getTraceId());
            log.error("=======token?????????????????????????????????===========");
            this.updateTokenTotalSupply();
            log.error("=======token?????????????????????????????????===========");
        } catch (Exception e) {
            log.error("????????????token?????????????????????", e);
        } finally {
            MDC.remove(CommonConstant.TRACE_ID);
            lock.unlock();
        }
    }

    /**
     * ??????ERC20???Erc721Enumeration token???????????????===???????????????
     *
     * @return void
     * @author huangyongpeng@matrixelements.com
     * @date 2021/1/18
     */
    public void updateTokenTotalSupply() {
        // ???????????????????????????????????????
        if (!AppStatusUtil.isRunning()) {
            return;
        }
        Set<ErcToken> updateParams = new ConcurrentHashSet<>();
        List<List<ErcToken>> batchList = new ArrayList<>();
        // ??????????????????Token????????????????????????????????????
        List<ErcToken> batch = new ArrayList<>();
        batchList.add(batch);
        for (ErcToken token : ercCache.getTokenCache().values()) {
            if (token.isDirty()) {
                updateParams.add(token);
            }
            if (!(token.getTypeEnum() == ErcTypeEnum.ERC20 || token.getIsSupportErc721Enumeration())) {
                continue;
            }
            if (batch.size() == TOKEN_BATCH_SIZE) {
                // ?????????????????????????????????????????????????????????????????????
                batch = new ArrayList<>();
                batchList.add(batch);
            }
            // ???????????????
            batch.add(token);
        }
        // ??????????????????Token totalSupply
        batchList.forEach(b -> {
            CountDownLatch latch = new CountDownLatch(b.size());
            for (ErcToken token : b) {
                TOKEN_UPDATE_POOL.submit(() -> {
                    try {
                        // ??????????????????
                        BigInteger totalSupply = ercServiceImpl.getTotalSupply(token.getAddress());
                        totalSupply = totalSupply == null ? BigInteger.ZERO : totalSupply;
                        if (token.getTotalSupply().compareTo(new BigDecimal(totalSupply)) != 0) {
                            // ?????????????????????????????????
                            token.setTotalSupply(new BigDecimal(totalSupply));
                            token.setUpdateTime(new Date());
                            updateParams.add(token);
                        }
                    } catch (Exception e) {
                        log.error("????????????ERC 20 token???????????????", e);
                    } finally {
                        latch.countDown();
                    }
                });
            }
            try {
                latch.await();
            } catch (InterruptedException e) {
                log.error("", e);
            }
        });
        if (!updateParams.isEmpty()) {
            // ??????????????????????????????????????????
            customTokenMapper.batchInsertOrUpdateSelective(new ArrayList<>(updateParams), Token.Column.values());
            updateParams.forEach(token -> token.setDirty(false));
        }
        updateTokenHolderCount();
    }

    /**
     * ??????token???????????????????????????
     *
     * @param
     * @return void
     * @author huangyongpeng@matrixelements.com
     * @date 2021/3/17
     */
    private void updateTokenHolderCount() {
        List<Token> updateTokenList = new ArrayList<>();
        List<TokenHolderCount> list = customTokenHolderMapper.findTokenHolderCount();
        List<Token> tokenList = tokenMapper.selectByExample(null);
        if (CollUtil.isNotEmpty(list) && CollUtil.isNotEmpty(tokenList)) {
            list.forEach(tokenHolderCount -> {
                tokenList.forEach(token -> {
                    if (token.getAddress().equalsIgnoreCase(tokenHolderCount.getTokenAddress())
                            && !token.getHolder().equals(tokenHolderCount.getTokenHolderCount())
                    ) {
                        token.setHolder(tokenHolderCount.getTokenHolderCount());
                        updateTokenList.add(token);
                    }
                });
            });
        }
        if (CollUtil.isNotEmpty(updateTokenList)) {
            customTokenMapper.batchInsertOrUpdateSelective(updateTokenList, Token.Column.values());
            log.info("??????token???????????????????????????{}", JSONUtil.toJsonStr(updateTokenList));
        }
    }

    /**
     * ??????token???????????????===???????????????
     * ???1??????????????????
     *
     * @param
     * @return void
     * @author huangyongpeng@matrixelements.com
     * @date 2021/2/1
     */
    @Scheduled(cron = "0 */1 * * * ?")
    public void cronIncrementUpdateTokenHolderBalance() {
        if (tokenHolderLock.tryLock()) {
            try {
                MDC.put(CommonConstant.TRACE_ID, CommonUtil.getTraceId());
                log.error("=======??????token?????????????????????????????????===========");
                incrementUpdateTokenHolderBalance(esErc20TxRepository, ErcTypeEnum.ERC20, this.getErc20TxSeq());
                incrementUpdateTokenHolderBalance(esErc721TxRepository, ErcTypeEnum.ERC721, this.getErc721TxSeq());
                log.error("=======??????token?????????????????????????????????===========");
            } catch (Exception e) {
                log.error("????????????token?????????????????????", e);
            } finally {
                MDC.remove(CommonConstant.TRACE_ID);
                tokenHolderLock.unlock();
            }
        } else {
            log.error("??????????????????token???????????????????????????,erc20TxSeq:{},erc721TxSeq:{}", erc20TxSeq, erc721TxSeq);
        }
    }

    private void incrementUpdateTokenHolderBalance(AbstractEsRepository abstractEsRepository, ErcTypeEnum typeEnum, Long txSeq) {
        // ???????????????????????????????????????
        if (!AppStatusUtil.isRunning()) {
            return;
        }
        try {
            List<TokenHolder> updateParams = new ArrayList<>();
            ESQueryBuilderConstructor constructor = new ESQueryBuilderConstructor();
            ESResult<ErcTx> queryResultFromES = new ESResult<>();
            constructor.setAsc("seq");
            constructor.setResult(new String[]{"seq", "from", "contract", "to"});
            ESQueryBuilders esQueryBuilders = new ESQueryBuilders();
            esQueryBuilders.listBuilders().add(QueryBuilders.rangeQuery("seq").gt(txSeq));
            constructor.must(esQueryBuilders);
            constructor.setUnmappedType("long");
            queryResultFromES = abstractEsRepository.search(constructor, ErcTx.class,
                    1, 5000);
            List<ErcTx> list = queryResultFromES.getRsData();
            if (CollUtil.isEmpty(list)) {
                return;
            }
            HashMap<String, HashSet<String>> map = new HashMap();
            list.sort(Comparator.comparing(ErcTx::getSeq));
            if (typeEnum == ErcTypeEnum.ERC20) {
                this.setErc20TxSeq(list.get(list.size() - 1).getSeq());
            } else if (typeEnum == ErcTypeEnum.ERC721) {
                this.setErc721TxSeq(list.get(list.size() - 1).getSeq());
            }
            list.forEach(v -> {
                if (map.containsKey(v.getContract())) {
                    // ???????????????0??????
                    if (!AddressUtil.isAddrZero(v.getTo())) {
                        map.get(v.getContract()).add(v.getTo());
                    }
                    if (!AddressUtil.isAddrZero(v.getFrom())) {
                        map.get(v.getContract()).add(v.getFrom());
                    }
                } else {
                    HashSet<String> addressSet = new HashSet<String>();
                    // ???????????????0??????
                    if (!AddressUtil.isAddrZero(v.getTo())) {
                        addressSet.add(v.getTo());
                    }
                    if (!AddressUtil.isAddrZero(v.getFrom())) {
                        addressSet.add(v.getFrom());
                    }
                    map.put(v.getContract(), addressSet);
                }
            });

            if (MapUtil.isNotEmpty(map)) {
//                AtomicInteger size = new AtomicInteger();
//                map.forEach((k, v) -> {
//                    size.addAndGet(v.size());
//                });
//
//                CountDownLatch latch = new CountDownLatch(size.get());
//                map.forEach((contract, addressSet) -> {
//                    addressSet.forEach(address -> {
//                        INCREMENT_HOLDER_UPDATE_POOL.submit(() -> {
//                            try {
//                                BigInteger balance = ercServiceImpl.getBalance(contract, typeEnum, address);
//                                TokenHolder holder = new TokenHolder();
//                                holder.setTokenAddress(contract);
//                                holder.setAddress(address);
//                                holder.setBalance(new BigDecimal(balance));
//                                holder.setUpdateTime(DateUtil.date());
//                                updateParams.add(holder);
//                            } catch (Exception e) {
//                                log.error(StrFormatter.format("????????????????????????,contract:{},address:{}", contract, address), e);
//                            } finally {
//                                latch.countDown();
//                            }
//                        });
//                    });
//                });
//                try {
//                    latch.await();
//                } catch (InterruptedException e) {
//                    log.error("", e);
//                }


                // ???????????????
                map.forEach((contract, addressSet) -> {
                    addressSet.forEach(address -> {
                        try {
                            BigInteger balance = ercServiceImpl.getBalance(contract, typeEnum, address);
                            TokenHolder holder = new TokenHolder();
                            holder.setTokenAddress(contract);
                            holder.setAddress(address);
                            holder.setBalance(new BigDecimal(balance));
                            holder.setUpdateTime(DateUtil.date());
                            updateParams.add(holder);
                            try {
                                TimeUnit.MILLISECONDS.sleep(100);
                            } catch (InterruptedException interruptedException) {
                                interruptedException.printStackTrace();
                            }
                        } catch (Exception e) {
                            log.error(StrFormatter.format("????????????????????????,contract:{},address:{}", contract, address), e);
                        }
                    });
                });
            }
            if (!updateParams.isEmpty()) {
                customTokenHolderMapper.batchUpdate(updateParams);
            }
        } catch (Exception e) {
            log.error("??????token?????????????????????", e);
        }
    }

    /**
     * ??????token???????????????===???????????????
     * ??????00:00:00????????????
     */
    @Scheduled(cron = "0 0 0 * * ?")
    public void updateTokenHolderBalance() {
        // ???????????????????????????????????????
        if (!AppStatusUtil.isRunning()) {
            return;
        }
        MDC.put(CommonConstant.TRACE_ID, CommonUtil.getTraceId());
        log.error("=======??????token?????????????????????????????????===========");
        try {
            tokenHolderLock.lock();
            // ????????????holder???balance
            List<TokenHolder> batch;
            int page = 0;
            do {
                TokenHolderExample condition = new TokenHolderExample();
                condition.setOrderByClause(" token_address asc, address asc limit " + page * HOLDER_BATCH_SIZE + "," + HOLDER_BATCH_SIZE);
                batch = tokenHolderMapper.selectByExample(condition);
                List<TokenHolder> updateParams = new ArrayList<>();
                if (!batch.isEmpty()) {
                    CountDownLatch latch = new CountDownLatch(batch.size());
                    batch.forEach(holder -> {
                        HOLDER_UPDATE_POOL.submit(() -> {
                            try {
                                // ?????????????????????
                                ErcToken token = ercCache.getTokenCache().get(holder.getTokenAddress());
                                if (token != null) {
                                    BigInteger balance = ercServiceImpl.getBalance(holder.getTokenAddress(), token.getTypeEnum(), holder.getAddress());
                                    if (holder.getBalance().compareTo(new BigDecimal(balance)) != 0) {
                                        // ????????????????????????????????????????????????????????????
                                        holder.setBalance(new BigDecimal(balance));
                                        holder.setUpdateTime(DateUtil.date());
                                        updateParams.add(holder);
                                    }
                                }
                            } catch (Exception e) {
                                log.error(StrFormatter.format("???????????????{}???????????????{}???????????????", holder.getAddress(), holder.getTokenAddress()), e);
                            } finally {
                                latch.countDown();
                            }
                        });
                    });
                    try {
                        latch.await();
                    } catch (InterruptedException e) {
                        log.error("", e);
                    }
                }
                if (!updateParams.isEmpty()) {
                    customTokenHolderMapper.batchUpdate(updateParams);
                }
                page++;
            } while (!batch.isEmpty());
        } catch (Exception e) {
            log.error("??????????????????????????????", e);
        } finally {
            tokenHolderLock.unlock();
        }
        log.error("=======??????token?????????????????????????????????===========");
        MDC.remove(CommonConstant.TRACE_ID);
    }

    /**
     * ??????token????????????=>????????????
     * ????????????1?????????
     *
     * @param
     * @return void
     * @author huangyongpeng@matrixelements.com
     * @date 2021/4/17
     */
    @Scheduled(cron = "0 0 1 */1 * ?")
    public void updateTokenInventory() {
        tokenInventoryLock.lock();
        try {
            MDC.put(CommonConstant.TRACE_ID, CommonUtil.getTraceId());
            log.error("=======??????token??????????????????????????????===========");
            updateTokenInventory(INVENTORY_UPDATE_POOL, 0, false);
            tokenInventoryUpdate.update(0, false, 0);
            log.error("=======??????token??????????????????????????????===========");
        } catch (Exception e) {
            log.error("??????token????????????", e);
        } finally {
            MDC.remove(CommonConstant.TRACE_ID);
            tokenInventoryLock.unlock();
        }
    }

    /**
     * ??????token????????????=>????????????
     * ???1????????????
     *
     * @param
     * @return void
     * @author huangyongpeng@matrixelements.com
     * @date 2021/2/1
     */
    @Scheduled(cron = "0 */1 * * * ?")
    public void cronIncrementUpdateTokenInventory() {
        if (tokenInventoryLock.tryLock()) {
            try {
                MDC.put(CommonConstant.TRACE_ID, CommonUtil.getTraceId());
                log.error("=======??????token??????????????????????????????===========");
                updateTokenInventory(INCREMENT_INVENTORY_UPDATE_POOL, this.getTokenInventoryPage(), true);
                log.error("=======??????token??????????????????????????????===========");
            } catch (Exception e) {
                log.error("????????????token??????????????????", e);
            } finally {
                MDC.remove(CommonConstant.TRACE_ID);
                tokenInventoryLock.unlock();
            }
        } else {
            log.error("??????token?????????????????????????????????????????????????????????{}", tokenInventoryPage);
        }
    }

    /**
     * ??????token????????????
     *
     * @param pool        ?????????
     * @param pageNum     ????????????
     * @param isIncrement ??????????????????
     * @return void
     * @author huangyongpeng@matrixelements.com
     * @date 2021/2/2
     */
    public void updateTokenInventory(ExecutorService pool, int pageNum, boolean isIncrement) {
        // ???????????????????????????????????????
        if (!AppStatusUtil.isRunning()) {
            return;
        }
        try {
            // ????????????token??????????????????
            List<TokenInventory> batch;
            int page = pageNum;
            boolean isUpdate;
            int num = 0;
            do {
                TokenInventoryExample condition = new TokenInventoryExample();
                condition.setOrderByClause(" create_time asc limit " + page * INVENTORY_BATCH_SIZE + "," + INVENTORY_BATCH_SIZE);
                batch = tokenInventoryMapper.selectByExample(condition);
                if (isIncrement) {
                    isUpdate = incrementTokenInventoryUpdate.getPageUpdate(pageNum, CollUtil.isEmpty(batch) ? 0 : batch.size());
                } else {
                    isUpdate = tokenInventoryUpdate.getPageUpdate(page, CollUtil.isEmpty(batch) ? 0 : batch.size());
                }
                List<TokenInventory> updateParams = new ArrayList<>();
                if (!batch.isEmpty() && !isUpdate) {
                    batch.forEach(inventory -> {
                        try {
                            String tokenURI = ercServiceImpl.getTokenURI(inventory.getTokenAddress(), new BigInteger(inventory.getTokenId()));
                            if (StrUtil.isNotBlank(tokenURI)) {
                                Request request = new Request.Builder().url(tokenURI).build();
                                try (Response response = client.newCall(request).execute()) {
                                    if (response.code() == 200) {
                                        String resp = response.body().string();
                                        TokenInventory newTi = JSON.parseObject(resp, TokenInventory.class);
                                        newTi.setUpdateTime(DateUtil.date());
                                        newTi.setTokenId(inventory.getTokenId());
                                        newTi.setTokenAddress(inventory.getTokenAddress());
                                        boolean changed = false;
                                        // ??????????????????????????????????????????????????????
                                        if (!newTi.getImage().equals(inventory.getImage())) {
                                            inventory.setImage(newTi.getImage());
                                            changed = true;
                                        }
                                        if (!newTi.getDescription().equals(inventory.getDescription())) {
                                            inventory.setDescription(newTi.getDescription());
                                            changed = true;
                                        }
                                        if (!newTi.getName().equals(inventory.getName())) {
                                            inventory.setName(newTi.getName());
                                            changed = true;
                                        }
                                        if (changed) {
                                            inventory.setUpdateTime(new Date());
                                            updateParams.add(inventory);
                                        }
                                    }
                                    if (response.code() == 404) {
                                        log.error("token_address[{}] token_id[{}] tokenURI [{}] ?????????", inventory.getTokenAddress(), inventory.getTokenId(), tokenURI);
                                    }
                                } catch (Exception e) {
                                    log.error(StrFormatter.format("??????TokenURI?????????token_address???{},token_id:{}", inventory.getTokenAddress(), inventory.getTokenId()), e);
                                }
                            } else {
                                log.error("??????TokenURI?????????token_address???{},token_id:{}", inventory.getTokenAddress(), inventory.getTokenId());
                            }
                        } catch (Exception e) {
                            log.error("??????token??????????????????", e);
                        }
                    });
                    // ?????????INVENTORY_BATCH_SIZE???????????????????????????
                    if (batch.size() == INVENTORY_BATCH_SIZE) {
                        num = page;
                        page++;
                    }
                    if (batch.size() != INVENTORY_BATCH_SIZE && !isIncrement) {
                        page++;
                    }
                }
                if (!updateParams.isEmpty()) {
                    customTokenInventoryMapper.batchInsertOrUpdateSelective(updateParams, TokenInventory.Column.values());
                    log.info("??????token????????????{}????????????{}", JSONUtil.toJsonStr(updateParams), page);
                }
                if (isIncrement) {
                    incrementTokenInventoryUpdate.update(pageNum, true, batch.size());
                    isUpdate = incrementTokenInventoryUpdate.getPageUpdate(pageNum, CollUtil.isEmpty(batch) ? 0 : batch.size());
                } else {
                    tokenInventoryUpdate.update(num, true, batch.size());
                    isUpdate = tokenInventoryUpdate.getPageUpdate(page, CollUtil.isEmpty(batch) ? 0 : batch.size());
                }
            } while (!batch.isEmpty() && !isUpdate);
            if (isIncrement) {
                this.setTokenInventoryPage(page);
                if (batch.size() != INVENTORY_BATCH_SIZE) {
                    incrementTokenInventoryUpdate.update(pageNum, false, batch.size());
                }
            }
        } catch (Exception e) {
            log.error("??????token??????????????????", e);
        }
    }

}
