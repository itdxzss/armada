package com.armada.account.state;

import com.armada.account.mapper.AccountImportDetailMapper;
import com.armada.account.model.entity.Account;
import com.armada.account.model.entity.AccountImportLoginResult;
import com.armada.account.model.entity.AccountImportLoginResultSettlement;
import com.armada.account.model.entity.AccountImportOnlinePhase;
import com.armada.account.model.entity.ImportResult;
import com.armada.account.service.AccountStateChangedEvent;
import org.springframework.stereotype.Service;

/**
 * 根据协议账号状态事件冻结账号导入明细的首次登录结果。
 *
 * <p>本结算器只处理已由导入自动上线派发的明细({@code online_phase=2})。
 * 普通手动上线、批量上线、换 IP 重登没有对应待结算明细时,SQL 更新 0 行并直接返回。</p>
 */
@Service
public class AccountImportLoginResultSettler implements AccountStateChangedSideEffect {

    private static final int WA_CODE_FORBIDDEN = 403;
    private static final int REASON_MAX_LENGTH = 255;
    private static final String STATE_ONLINE = "ONLINE";
    private static final String STATE_OFFLINE = "OFFLINE";
    private static final String STATE_NEED_REAUTH = "NEED_REAUTH";
    private static final String STATE_LOGGED_OUT = "LOGGED_OUT";
    private static final String STATE_DEVICE_REMOVED = "DEVICE_REMOVED";
    private static final String REASON_FORBIDDEN = "FORBIDDEN";

    private final AccountImportDetailMapper detailMapper;

    /**
     * 创建账号导入登录结果结算器。
     *
     * @param detailMapper 账号导入明细 mapper
     */
    public AccountImportLoginResultSettler(AccountImportDetailMapper detailMapper) {
        this.detailMapper = detailMapper;
    }

    /**
     * 在账号通用状态已经收敛成功后,尝试冻结导入明细的首次登录结果。
     *
     * <p>这里不直接判断“是否导入触发上线”:判断交给 SQL 的 {@code online_phase=DISPATCHED}
     * 条件完成。普通上线或非导入批量上线没有待结算明细时,本方法会变成幂等 no-op。</p>
     */
    @Override
    public void afterStateChanged(Account account, AccountStateChangedEvent event, long occurredAt) {
        LoginSettlement settlement = resolve(event);
        if (settlement == null) {
            return;
        }
        detailMapper.settleDispatchedLoginResult(toMapperParams(account, settlement, occurredAt));
    }

    /**
     * 组装导入明细结算 SQL 的参数对象。
     *
     * <p>SQL 会同时校验账号 ID、成功导入结果、已派发阶段和派发时间,
     * 防止普通账号状态事件或旧事件错误冻结导入明细。</p>
     *
     * @param account    当前协议事件匹配到的账号
     * @param settlement 已解析出的导入登录结果
     * @param occurredAt 协议事件发生时间(epoch 毫秒),写入 login_settled_at
     * @return Mapper 参数对象
     */
    private static AccountImportLoginResultSettlement toMapperParams(Account account,
                                                                     LoginSettlement settlement,
                                                                     long occurredAt) {
        AccountImportLoginResultSettlement params = new AccountImportLoginResultSettlement();
        params.setAccountId(account.getId());
        params.setDispatchedPhase(AccountImportOnlinePhase.DISPATCHED);
        params.setSettledPhase(AccountImportOnlinePhase.SETTLED);
        params.setSuccessParseResult(ImportResult.SUCCESS.getCode());
        params.setLoginResult(settlement.loginResult());
        params.setLoginSettledAt(occurredAt);
        params.setLoginReason(settlement.reason());
        return params;
    }

    /**
     * 将协议层状态事件映射为导入明细的首次登录结果。
     *
     * <p>只有能代表“本次上线尝试已有终态”的事件才返回结算结果:
     * ONLINE 记成功;NEED_REAUTH+403 记封禁;认证失效类状态记密钥异常;
     * OFFLINE 记上线失败。PROXY_FAILED 会触发换 IP 重试,不在这里冻结导入结果。
     * RECONNECTING、CONNECTING 等中间态返回 null,等待后续终态。</p>
     *
     * @param event 协议账号状态事件
     * @return 可冻结到明细表的登录结果;中间态或未知状态返回 null
     */
    private static LoginSettlement resolve(AccountStateChangedEvent event) {
        String to = normalize(event.to());
        String semantic = normalize(event.semantic());
        if (STATE_ONLINE.equals(to)) {
            return new LoginSettlement(AccountImportLoginResult.SUCCESS, null);
        }
        if (STATE_NEED_REAUTH.equals(to) && isForbidden(event.rawCode())) {
            return new LoginSettlement(AccountImportLoginResult.BANNED, REASON_FORBIDDEN);
        }
        if (STATE_NEED_REAUTH.equals(to) || STATE_LOGGED_OUT.equals(to) || STATE_DEVICE_REMOVED.equals(to)) {
            return new LoginSettlement(AccountImportLoginResult.KEY_ABNORMAL, reason(semantic, to));
        }
        if (STATE_OFFLINE.equals(to)) {
            return new LoginSettlement(AccountImportLoginResult.FAILED, reason(semantic, to));
        }
        return null;
    }

    /**
     * 判断协议层 rawCode 是否表示 WhatsApp 禁用/封禁。
     *
     * @param rawCode 协议层原始状态码
     * @return true 表示按封禁结果冻结导入明细
     */
    private static boolean isForbidden(Integer rawCode) {
        return rawCode != null && rawCode == WA_CODE_FORBIDDEN;
    }

    /**
     * 选择写入明细表的失败原因。
     *
     * <p>优先使用 semantic,因为它比 to 状态更接近协议层失败语义;
     * semantic 为空时退回 to 状态。结果会裁剪到 login_reason 列宽。</p>
     *
     * @param semantic 协议层语义码
     * @param to       协议层目标状态
     * @return 可落库的原因字符串;成功结算场景不调用本方法
     */
    private static String reason(String semantic, String to) {
        String value = semantic == null || semantic.isBlank() ? to : semantic;
        if (value == null || value.length() <= REASON_MAX_LENGTH) {
            return value;
        }
        return value.substring(0, REASON_MAX_LENGTH);
    }

    /**
     * 规整协议层字符串,使大小写和前后空格不会影响状态判断。
     *
     * @param value 协议层原始字符串
     * @return 大写后的字符串;原值为空时返回 null
     */
    private static String normalize(String value) {
        return value == null ? null : value.trim().toUpperCase();
    }

    /**
     * 结算器内部结果:登录结果码和可选失败原因。
     *
     * @param loginResult 写入 account_import_detail.login_result 的结果码
     * @param reason      写入 account_import_detail.login_reason 的原因;成功时为空
     */
    private record LoginSettlement(int loginResult, String reason) {
    }
}
