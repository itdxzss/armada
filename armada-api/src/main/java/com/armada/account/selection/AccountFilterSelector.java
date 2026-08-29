package com.armada.account.selection;

import com.armada.account.selection.mapper.AccountFilterSelectionMapper;
import com.armada.account.selection.model.SelectedAccount;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 按账号筛选条件圈出可发送账号。
 *
 * <p>通讯录营销与超链任务共用同一份圈号口径，因此本服务落在账号域，
 * 而不是任一消费方的包里。</p>
 *
 * <p><b>强制注入</b>（设计 §2.7）：无论筛选条件写了什么，都只圈「正常且未导出」的账号。
 * 筛选条件为空时语义是「全部有效账号」，不是「不圈号」。</p>
 */
@Component
public class AccountFilterSelector {

    /** 账号状态：正常。取值见 {@code V005__account.sql} 的 {@code account_state} 列注释。 */
    public static final int ACCOUNT_STATE_NORMAL = 2;

    /** 账号状态：已导出。强制排除。 */
    public static final int ACCOUNT_STATE_EXPORTED = 4;

    private final AccountFilterSelectionMapper mapper;
    private final ObjectMapper objectMapper;

    /**
     * 创建圈号服务。
     *
     * @param mapper 圈号数据访问
     * @param objectMapper JSON 解码器
     */
    public AccountFilterSelector(AccountFilterSelectionMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 按归一化后的筛选 JSON 圈号。
     *
     * @param normalizedFilterJson 归一化筛选 JSON；null、空或非法均视为不限定
     * @param limit 结果上限；非正数直接返回空列表，避免退化成全表扫描
     * @return 命中账号，可能为空
     */
    public List<SelectedAccount> select(String normalizedFilterJson, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        AccountFilterCriteria criteria =
                AccountFilterCriteria.parse(normalizedFilterJson, objectMapper);
        List<SelectedAccount> rows = mapper.selectAccounts(
                criteria, ACCOUNT_STATE_NORMAL, ACCOUNT_STATE_EXPORTED, limit);
        return rows == null ? List.of() : List.copyOf(rows);
    }
}
