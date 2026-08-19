package com.armada.platform.protocol.model.command;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 「群信息设置」命令的协议可执行 wire payload。
 *
 * <p><b>留空即别动</b>：设置项字段全部可空，运营在表单里选了「不操作」的项一律传 {@code null}。
 * 类上的 {@link JsonInclude} 保证它们序列化时**整个字段不出现**，而不是输出 {@code null}。
 * 这不是洁癖：拉群进的是客户已经在用的老群，协议端拿到一个显式的 {@code null} 无从判断是
 * 「别动」还是「清空」，一旦按清空执行就把客户自己配的群资料抹了。</p>
 *
 * <p>手机号字段必须叫 {@code wsPhone}：coordinator 的 ExtractPhone 用它做 group-action 族路由，
 * 安卓节点用它 Resolve 会话。改名不会报错，只会让命令被 coordinator 以 "phone unresolvable"
 * 判为业务拒绝、提交 offset 后静默丢弃，控端于是永远等不到结果、无限重试。</p>
 *
 * @param tenantId 租户 ID
 * @param pullTaskId 拉群任务 ID
 * @param groupExecutionId 群执行行 ID
 * @param actionId 群信息设置动作行 ID
 * @param accountId 执行账号在 armada 的账号 ID
 * @param protocolAccountId 执行账号在协议层的账号 ID
 * @param wsPhone 执行账号手机号，协议侧据此路由与 Resolve 会话
 * @param protocolBackend 协议后端：WEB / ANDROID
 * @param groupJid 目标群 JID，必填；本命令只改老群，没有它无从定位
 * @param attemptNo 尝试序号
 * @param timeoutMs 协议侧执行超时毫秒
 * @param source 业务来源，固定 {@code pull_task_group_profile}
 * @param subject 群名称；留空表示不改群名
 * @param avatar 群头像，已转好的 640×640 方形 JPEG，base64 内嵌；留空表示不改头像
 * @param description 群描述；留空表示不改描述
 * @param sendMessagesAllowed 是否允许全体成员发言；留空表示不改禁言设置
 * @param editGroupSettingsAllowed 是否允许全体成员编辑群资料，同时决定谁能取群邀请链接；
 *        留空表示不改该权限
 * @param addMembersAllowed 是否允许全体成员加人；留空表示不改该权限
 * @param joinApprovalEnabled 是否开启入群审批；留空表示不改该开关
 * @param ephemeralDurationSeconds 限时消息秒数，0 表示关闭；留空表示不改限时消息
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProtocolPullTaskGroupProfilePayload(
        Long tenantId,
        Long pullTaskId,
        Long groupExecutionId,
        Long actionId,
        Long accountId,
        String protocolAccountId,
        String wsPhone,
        String protocolBackend,
        String groupJid,
        Integer attemptNo,
        int timeoutMs,
        String source,
        String subject,
        Avatar avatar,
        String description,
        Boolean sendMessagesAllowed,
        Boolean editGroupSettingsAllowed,
        Boolean addMembersAllowed,
        Boolean joinApprovalEnabled,
        Integer ephemeralDurationSeconds
) {

    /**
     * 群头像内容。
     *
     * <p>走 base64 内嵌而不是 Redis 资源引用或 URL：协议层进程读不到 armada 的本地盘，
     * 而群头像是 500KB 以内的小图，内嵌一次就走完，不值得为它多引一条资源生命周期。</p>
     *
     * <p>armada 侧已经转成 WhatsApp 要求的 640×640 方形 JPEG，协议两侧**纯透传**，
     * 不要再缩放、裁切或改格式。Web 那条路的底层库自带缩放而安卓自研协议没有，
     * 转码不统一放在 armada，两条路的结果就不一致。</p>
     *
     * @param base64 图片 base64 编码内容，640×640 JPEG
     * @param mimetype 图片 MIME 类型，固定 {@code image/jpeg}
     */
    public record Avatar(String base64, String mimetype) {
    }
}
