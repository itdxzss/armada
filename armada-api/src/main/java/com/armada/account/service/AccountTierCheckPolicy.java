package com.armada.account.service;

import com.armada.account.model.dto.AccountTierCheckCandidate;
import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.security.AuthPrincipal;
import com.armada.shared.tenant.TenantContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** 变化推断、访问与快照摘要集中在本功能，不修改账号有效类型或状态。 */
public final class AccountTierCheckPolicy {
    /** 单批冻结上限，避免无界内存/插入。 */ public static final int MAX_ACCOUNTS = 20_000;
    /** 首次检测。 */ public static final int FIRST = 0;
    /** 不变。 */ public static final int SAME = 1;
    /** 推断上升。 */ public static final int UP = 2;
    /** 推断下降。 */ public static final int DOWN = 3;
    /** 变化但方向不明。 */ public static final int UNCERTAIN = 4;
    /** 上次可查，本次未查到。 */ public static final int LOST = 5;
    private static final List<String> CHAIN = List.of("SUSPICIOUS", "TIER_0", "TIER_1", "TIER_2", "TIER_3");
    private static final ObjectMapper JSON = new ObjectMapper();
    private AccountTierCheckPolicy() {}
    /** 仅在已确认设计的推断链内给方向；UNTIERED 与未来值不推断高低。 */
    public static int change(String before, String after, boolean successful) {
        if (before == null) return FIRST;
        if (!successful || after == null) return LOST;
        if (before.equals(after)) return SAME;
        int a = CHAIN.indexOf(before), b = CHAIN.indexOf(after);
        return a < 0 || b < 0 ? UNCERTAIN : b > a ? UP : DOWN;
    }
    /** 当前可信身份必须对应线程租户上下文。 */
    public static void require(AuthPrincipal p) {
        if (p == null || !Objects.equals(p.tenantId(), TenantContext.get()))
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
    }
    /** 沿用租户管理员查看全部数据的现有账号列表语义。 */
    public static boolean admin(AuthPrincipal p) { return p.roleCodes().contains("TENANT_ADMIN"); }
    /** 未分配账号租户共享；已归属账号只有本人或租户管理员可操作。 */
    public static boolean owns(AuthPrincipal p, AccountTierCheckCandidate a) {
        return a != null && (admin(p) || a.ownerUserId() == null || Objects.equals(a.ownerUserId(), p.userId()));
    }
    /** 唯一在线判定沿用 AccountLoginStateCode.ONLINE。 */
    public static boolean online(AccountTierCheckCandidate a) {
        return a != null && Integer.valueOf(AccountLoginStateCode.ONLINE).equals(a.loginState());
    }
    /** 只散列身份/版本，不包含凭据内容。 */
    public static String identity(AccountTierCheckCandidate a) {
        return hash(a.id() + ":" + a.wsPhone() + ":" + a.protocolBackend() + ":" + a.protocolAccountId() + ":" + a.credentialVersion());
    }
    /** 请求/预览的稳定摘要，不用于鉴权。 */
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("JDK 缺少 SHA-256", ex); }
    }
    /** 读取本功能保存的探针集合；不做无法解析时的静默兜底。 */
    public static List<Long> probes(String json) {
        try { return JSON.readValue(json, new TypeReference<List<Long>>() {}); }
        catch (java.io.IOException ex) { throw new BusinessException(ErrorCode.VALIDATION, "批次探针配置无法解析"); }
    }
    /** 返回现有业务参数错误形状。 */
    public static BusinessException invalid(String text) { return new BusinessException(ErrorCode.VALIDATION, text); }
}
