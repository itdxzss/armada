package com.armada.hyperlink.task.service;

import com.armada.hyperlink.task.mapper.HyperlinkTaskRecipientMapper;
import com.armada.hyperlink.task.model.entity.HyperlinkBillingReservation;
import com.armada.hyperlink.task.model.vo.HyperlinkRecipientCountryCount;
import com.armada.hyperlink.task.model.vo.HyperlinkTaskQuoteBreakdownVO;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** 从唯一 recipient 事实计算任务最终应结算的人数与金额。 */
@Service
public class HyperlinkBillingConsumptionService {
    private static final TypeReference<List<HyperlinkTaskQuoteBreakdownVO>> BREAKDOWN_TYPE =
            new TypeReference<>() { };
    private final HyperlinkTaskRecipientMapper recipientMapper;
    private final ObjectMapper objectMapper;

    public HyperlinkBillingConsumptionService(HyperlinkTaskRecipientMapper recipientMapper,
            ObjectMapper objectMapper) {
        this.recipientMapper = recipientMapper;
        this.objectMapper = objectMapper;
    }

    /** 返回任务当前唯一已实际发送 recipient 的最终计费快照。 */
    public Consumption snapshot(long taskId, HyperlinkBillingReservation billing) {
        if (recipientMapper.countSendingByTaskId(taskId) > 0) {
            throw new BusinessException(ErrorCode.HYPERLINK_TASK_STATE_CONFLICT,
                    "任务仍有发送结果未收敛，暂不能结算");
        }
        List<HyperlinkRecipientCountryCount> sent = recipientMapper.selectSentCountryCounts(taskId);
        Map<String, HyperlinkTaskQuoteBreakdownVO> prices = parsePrices(billing.getPricingBreakdown());
        BigDecimal amount = BigDecimal.ZERO;
        long sendCount = 0;
        for (HyperlinkRecipientCountryCount row : sent) {
            HyperlinkTaskQuoteBreakdownVO price = prices.get(row.countryIso2());
            if (price == null || price.unitPrice() == null) {
                throw new BusinessException(ErrorCode.HYPERLINK_BILLING_UNAVAILABLE,
                        "实际发送国家缺少冻结报价");
            }
            sendCount += row.recipientCount();
            amount = amount.add(price.unitPrice().multiply(BigDecimal.valueOf(row.recipientCount())));
        }
        if (sendCount > billing.getQuotedRecipientCount()
                || amount.compareTo(billing.getQuotedAmount()) > 0) {
            throw new BusinessException(ErrorCode.HYPERLINK_BILLING_UNAVAILABLE,
                    "实际发送消费超过冻结报价");
        }
        return new Consumption(sendCount, amount);
    }

    /** 领取完成后校验 recipient 人数与冻结报价一致。 */
    public int recipientCount(long taskId) {
        return recipientMapper.countByTaskId(taskId);
    }

    private Map<String, HyperlinkTaskQuoteBreakdownVO> parsePrices(String json) {
        try {
            return objectMapper.readValue(json, BREAKDOWN_TYPE).stream().collect(Collectors.toMap(
                    HyperlinkTaskQuoteBreakdownVO::recipientCountryIso2,
                    Function.identity(),
                    (left, right) -> left));
        } catch (JsonProcessingException | RuntimeException exception) {
            throw new BusinessException(ErrorCode.HYPERLINK_BILLING_UNAVAILABLE,
                    "冻结报价明细无法解析");
        }
    }

    /** 任务最终唯一发送人数与应结算金额。 */
    public record Consumption(long sendCount, BigDecimal amount) { }
}
