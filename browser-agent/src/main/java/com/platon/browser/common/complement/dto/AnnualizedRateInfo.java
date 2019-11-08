package com.platon.browser.common.complement.dto;

import com.alibaba.fastjson.JSON;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * @Auther: Chendongming
 * @Date: 2019/8/21 15:09
 * @Description: 年化率信息bean
 */
@Data
@Builder
@AllArgsConstructor
@Accessors(chain = true)
public class AnnualizedRateInfo {
    private List<PeriodValueElement> profit;
    private List<PeriodValueElement> cost;
    private List<SlashInfo> slash;
    public String toJSONString(){return JSON.toJSONString(this);}
}
