package com.platon.browser.service;

import com.alibaba.fastjson.JSON;
import com.platon.bech32.Bech32;
import com.platon.browser.elasticsearch.dto.Block;
import com.platon.browser.elasticsearch.dto.NodeOpt;
import com.platon.browser.elasticsearch.dto.Transaction;
import com.platon.browser.utils.HexUtil;
import com.platon.parameters.NetworkParameters;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;

import java.math.BigInteger;
import java.util.*;

@Slf4j
@Data
public class BlockResult {


    public static class AddressCount {

        private String address;

        private Integer count = 0;

        public String get(String newAddress, int addressReusedTimes) {
            if (count == 0 || count >= addressReusedTimes) {
                address = newAddress;
                count = 1;
                return address;
            }
            count++;
            return address;
        }

    }

    private static final AddressCount FROM_ADDRESS = new AddressCount();

    private static final AddressCount TO_ADDRESS = new AddressCount();

    private Block block;

    private List<Transaction> transactionList = new ArrayList<>();

    private List<NodeOpt> nodeOptList = new ArrayList<>();

    public void buildAssociation(BigInteger blockNumber, String nodeId, long nodeOptId, int addressReusedTimes) {
        block.setNum(blockNumber.longValue());
        block.setNodeId(nodeId);
        String blockHash = HexUtil.prefix(DigestUtils.sha256Hex(block.toString()));
        block.setHash(blockHash);
        int i = 0;
        for (Transaction tx : transactionList) {
            tx.setBHash(blockHash);
            tx.setNum(blockNumber.longValue());
            String txHash = HexUtil.prefix(DigestUtils.sha256Hex(UUID.randomUUID().toString()));
            tx.setHash(txHash);
            String from = HexUtil.prefix(DigestUtils.sha1Hex(txHash));
            tx.setFrom(FROM_ADDRESS.get(Bech32.addressEncode(NetworkParameters.getHrp(), from),addressReusedTimes));
            String to = HexUtil.prefix(DigestUtils.sha1Hex(from));
            tx.setTo(TO_ADDRESS.get(Bech32.addressEncode(NetworkParameters.getHrp(), to),addressReusedTimes));
            tx.setIndex(i);
            //long seq = tx.getNum() * 100000 + i;
            //tx.setSeq(seq);
            i++;
        }

        for (NodeOpt nodeOpt : nodeOptList) {
            nodeOpt.setId(nodeOptId++);
            nodeOpt.setNodeId(nodeId);
            nodeOpt.setBNum(blockNumber.longValue());
        }
    }

    private static final String nodeIdPre = "0x";

    /**
     * ??????nodId????????????0x + 128??????nodeId??????128????????????0???
     *
     * @param nodeId ?????????nodeId???
     * @return java.lang.String
     * @author huangyongpeng@matrixelements.com
     * @date 2021/3/12
     */
    public static String createNodeId(long nodeId) {
        return nodeIdPre + String.format("%0128d", nodeId);
    }


    /**
     * ????????????0??????
     *
     * @param pre           ??????
     * @param complementNum ??????????????????
     * @param num           ?????????
     * @return java.lang.String
     * @author huangyongpeng@matrixelements.com
     * @date 2021/3/12
     */
    public static String createNum(String pre, int complementNum, long num) {
        return pre + String.format("%0" + complementNum + "d", num);
    }

    /**
     * ??????[start,end]??????????????????
     *
     * @param start ?????????
     * @param end   ?????????
     * @return int
     * @author huangyongpeng@matrixelements.com
     * @date 2021/3/12
     */
    public static int getRandom(int start, int end) {
        if (end > start) {
            return (int) (Math.random() * (end - start + 1)) + start;
        } else {
            log.error("end??????start???");
            return 0;
        }
    }

    /**
     * ???[start,end]????????????noRepetitionNum??????
     *
     * @param start
     * @param end
     * @param noRepetitionNum
     * @return java.util.Set<java.lang.Integer>
     * @author huangyongpeng@matrixelements.com
     * @date 2021/3/12
     */
    public static Set<Integer> getNoRepetitionRandom(int start, int end, int noRepetitionNum) {
        Set<Integer> noRepetitionNums = new HashSet<Integer>(noRepetitionNum);
        while (true) {
            noRepetitionNums.add(getRandom(start, end));
            if (noRepetitionNums.size() == noRepetitionNum) {
                break;
            }
        }
        return noRepetitionNums;
    }

    public static void main(String[] args) {
        System.out.println("??????????????????" + JSON.toJSONString(getNoRepetitionRandom(100, 120, 5)));
    }

}
