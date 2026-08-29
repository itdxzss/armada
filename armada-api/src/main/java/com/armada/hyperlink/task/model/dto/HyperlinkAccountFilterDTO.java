package com.armada.hyperlink.task.model.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.math.BigDecimal;
import java.util.List;

/**
 * 超链任务发信账号范围的白名单筛选合同与持久化快照。
 *
 * <p>前端创建、编辑和账号数量试算均提交该结构；保存任务时，服务端先通过
 * {@code HyperlinkAccountFilterNormalizer} 校验、去重和归一化，再序列化到
 * {@code hyperlink_task.account_filter}。运行时必须重新解析同一快照，禁止根据账号当前情况改写
 * 用户已经确认的筛选条件。</p>
 *
 * <p>所有字段除 {@code filterSchemaVersion} 外均可不筛选。画像事实为 {@code null} 表示未知；
 * 配置好友数、注册天数、轮号状态或拉群权限等条件后，未知画像不会被当作零值或否定值参与匹配。
 * 未声明字段通过 {@link JsonAnySetter} 失败关闭，避免新旧版本静默丢失筛选条件。</p>
 */
public record HyperlinkAccountFilterDTO(
        /** 筛选快照结构版本；当前只能为 1。 */
        Integer filterSchemaVersion,
        /** 包含国家，两位 ISO 3166-1 alpha-2 代码；空列表表示不限。 */
        List<String> countryIso2s,
        /** 排除国家，两位 ISO 代码；不得与包含国家出现相同值。 */
        List<String> excludeCountryIso2s,
        /** 大洲代码：七大洲稳定英文枚举之一；为空表示不限。 */
        String continent,
        /** 账号分组 ID 集合；保存前去重并按 ID 排序。 */
        List<Long> groupIds,
        /** 推广渠道 ID 集合；保存前去重并按 ID 排序。 */
        List<Long> channelIds,
        /** 接入协议标识，保存前去除首尾空白并转为大写。 */
        String protocolId,
        /** 在线状态：{@code ONLINE} 在线、{@code OFFLINE} 离线。 */
        String onlineStatus,
        /** 轮号状态：0 未轮号、1 轮号中、2 成功、3 失败。 */
        Integer rotationStatus,
        /** 账号类型：1 个人、2 商业。 */
        Integer accountType,
        /** 设备平台六值，由安卓/苹果、个人/商业及主设备/分身设备组合表达。 */
        String platform,
        /** 设备接入类型：{@code web5} 分身设备、{@code native6} 主设备。 */
        String widType,
        /** 凭据导入方式：{@code six_segment} 六段、{@code full_param} 全参数。 */
        String importMode,
        /** 是否允许被拉群；为空表示不筛选，不能把未采集画像当作 false。 */
        Boolean groupInviteAllowed,
        /** WhatsApp 手机号片段，最长 32 字符，用于包含匹配。 */
        String phone,
        /** 账号导入批次 ID；必须是正整数。 */
        Long importBatchId,
        /** 运营来源：0 买量、1 自登、2 买入、3 转入、4 群扫码。 */
        Integer source,
        /** 双向好友数下限，闭区间；0 在归一化后表示不限。 */
        Integer friendCountMin,
        /** 双向好友数上限，闭区间；0 在归一化后表示不限。 */
        Integer friendCountMax,
        /** 存活天数下限，最多一位小数；由观察时刻减账号入库时间计算。 */
        BigDecimal retentionDaysMin,
        /** 存活天数上限，最多一位小数；由观察时刻减账号入库时间计算。 */
        BigDecimal retentionDaysMax,
        /** WhatsApp 注册天数下限，正整数，按完整自然天向下取整。 */
        Integer registerDaysMin,
        /** WhatsApp 注册天数上限，正整数，按完整自然天向下取整。 */
        Integer registerDaysMax,
        /** 账号入库时间下界，epoch 毫秒，包含该时刻。 */
        Long createdAtFrom,
        /** 账号入库时间上界，epoch 毫秒，不包含该时刻。 */
        Long createdAtTo) {

    /** Spring 默认忽略未知键；账号筛选快照必须在类型局部改为 fail-closed。 */
    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignoredValue) {
        throw new UnknownFieldException(field);
    }

    /** 供超链任务局部 JSON 异常处理识别，避免修改全局 Jackson 容错口径。 */
    public static final class UnknownFieldException extends RuntimeException {
        private final String field;

        public UnknownFieldException(String field) {
            super("accountFilter 未知字段: " + field);
            this.field = field;
        }

        public String field() {
            return field;
        }
    }
}
