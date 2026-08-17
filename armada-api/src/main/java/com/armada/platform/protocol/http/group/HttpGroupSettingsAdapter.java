package com.armada.platform.protocol.http.group;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.http.ProtocolHttpExecutor;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.routing.GroupSettingsBackend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link GroupSettingsPort} 的协议层 HTTP 适配器。
 *
 * <p>本类负责把 Armada 的秒数或布尔权限语义转换为 armada-protocol 约定的路径和
 * {@code mode} 字符串，并把协议账号放入请求体供 master gateway 路由 owner worker。
 * 执行账号选择、管理员权限判断、超时回读确认和业务异常转换均由上层 Service 负责。</p>
 *
 * <p>“通过链接邀请”使用协议层封装的 WhatsApp UpdateGroupProperty MEX mutation，
 * 与添加成员和入群审批保持为三个独立设置。</p>
 */
public class HttpGroupSettingsAdapter implements GroupSettingsBackend {

    /** 当前适配器的低层协议调用日志记录器。 */
    private static final Logger log = LoggerFactory.getLogger(HttpGroupSettingsAdapter.class);

    /** 关闭限时消息对应的秒数。 */
    private static final int EPHEMERAL_OFF_SECONDS = 0;

    /** 24 小时限时消息对应的秒数。 */
    private static final int EPHEMERAL_24_HOURS_SECONDS = 86_400;

    /** 7 天限时消息对应的秒数。 */
    private static final int EPHEMERAL_7_DAYS_SECONDS = 604_800;

    /** 90 天限时消息对应的秒数。 */
    private static final int EPHEMERAL_90_DAYS_SECONDS = 7_776_000;

    /** 限时消息设置接口路径后缀。 */
    private static final String EPHEMERAL_PATH = "/settings/ephemeral";

    /** 群资料编辑权限接口路径后缀。 */
    private static final String EDIT_GROUP_SETTINGS_PATH = "/settings/locked";

    /** 群发言权限接口路径后缀。 */
    private static final String SEND_MESSAGES_PATH = "/settings/announcement";

    /** 普通成员添加成员权限接口路径后缀。 */
    private static final String ADD_MEMBERS_PATH = "/settings/member-add-mode";

    /** 普通成员访问群邀请链接权限接口路径后缀。 */
    private static final String INVITE_VIA_LINK_PATH = "/settings/member-link-mode";

    /** 新成员入群审批接口路径后缀。 */
    private static final String JOIN_APPROVAL_PATH = "/settings/join-approval";

    /** 关闭限时消息或关闭审批使用的协议 mode。 */
    private static final String MODE_OFF = "off";

    /** 24 小时限时消息协议 mode。 */
    private static final String MODE_24_HOURS = "24h";

    /** 7 天限时消息协议 mode。 */
    private static final String MODE_7_DAYS = "7d";

    /** 90 天限时消息协议 mode。 */
    private static final String MODE_90_DAYS = "90d";

    /** 仅管理员可以编辑群资料的协议 mode。 */
    private static final String MODE_LOCKED = "locked";

    /** 所有成员可以编辑群资料的协议 mode。 */
    private static final String MODE_UNLOCKED = "unlocked";

    /** 仅管理员可以发言的协议 mode。 */
    private static final String MODE_ANNOUNCEMENT = "announcement";

    /** 所有成员可以发言的协议 mode。 */
    private static final String MODE_NOT_ANNOUNCEMENT = "not_announcement";

    /** 所有成员可以直接添加其他成员的协议 mode。 */
    private static final String MODE_ALL_MEMBER_ADD = "all_member_add";

    /** 仅管理员可以添加其他成员的协议 mode。 */
    private static final String MODE_ADMIN_ADD = "admin_add";

    /** 所有成员可访问和分享邀请链接。 */
    private static final String MODE_ALL_MEMBER_LINK = "all_member_link";

    /** 仅群主和管理员可访问邀请链接。 */
    private static final String MODE_ADMIN_LINK = "admin_link";

    /** 开启新成员入群审批的协议 mode。 */
    private static final String MODE_ON = "on";

    /** 统一协议层 HTTP 执行器。 */
    private final ProtocolHttpExecutor httpExecutor;

    /**
     * 创建群设置 HTTP 适配器。
     *
     * @param httpExecutor 已配置协议层 baseUrl、鉴权和超时的统一 HTTP 执行器
     */
    public HttpGroupSettingsAdapter(ProtocolHttpExecutor httpExecutor) {
        this.httpExecutor = httpExecutor;
    }

    @Override
    public ProtocolBackend backend() {
        return ProtocolBackend.WEB;
    }

    /**
     * 修改 WhatsApp 群限时消息周期。
     *
     * <p>Armada 内部使用秒数，协议层只接受 {@code off/24h/7d/90d} 四档 mode；
     * 其它秒数直接按参数错误拒绝，不向协议层发送模糊值。</p>
     *
     * @param protocolAccountId 协议层账号句柄
     * @param groupJid          WhatsApp 群 JID
     * @param durationSeconds   0、86400、604800 或 7776000 秒
     * @throws ProtocolException 当秒数不受支持、参数缺失或协议调用失败时抛出
     */
    @Override
    public void setEphemeralDuration(
            ProtocolAccountRef account,
            String groupJid,
            int durationSeconds) {
        String mode = switch (durationSeconds) {
            case EPHEMERAL_OFF_SECONDS -> MODE_OFF;
            case EPHEMERAL_24_HOURS_SECONDS -> MODE_24_HOURS;
            case EPHEMERAL_7_DAYS_SECONDS -> MODE_7_DAYS;
            case EPHEMERAL_90_DAYS_SECONDS -> MODE_90_DAYS;
            default -> throw new ProtocolException(
                    ProtocolErrorCode.BAD_REQUEST,
                    "不支持的群限时消息秒数: " + durationSeconds);
        };
        postMode(account, groupJid, EPHEMERAL_PATH, mode);
    }

    /**
     * 设置普通成员是否允许编辑群名称、头像等群资料。
     *
     * <p>业务语义 {@code enabled=true} 映射为协议 {@code unlocked}；false 映射为
     * {@code locked}，禁止直接把布尔值透传给协议层。</p>
     *
     * @param protocolAccountId 协议层账号句柄
     * @param groupJid          WhatsApp 群 JID
     * @param enabled           true 表示普通成员可编辑群设置
     * @throws ProtocolException 当参数缺失或协议调用失败时抛出
     */
    @Override
    public void setEditGroupSettingsAllowed(
            ProtocolAccountRef account, String groupJid, boolean enabled) {
        postMode(account, groupJid, EDIT_GROUP_SETTINGS_PATH,
                enabled ? MODE_UNLOCKED : MODE_LOCKED);
    }

    /**
     * 设置群是否允许所有成员发送新消息。
     *
     * <p>{@code enabled=false} 会切换为仅管理员发言的 announcement 模式；true 切回
     * not_announcement。该设置只改变发言权限，不发送公告文本。</p>
     *
     * @param protocolAccountId 协议层账号句柄
     * @param groupJid          WhatsApp 群 JID
     * @param enabled           true 表示所有成员可以发送新消息
     * @throws ProtocolException 当参数缺失或协议调用失败时抛出
     */
    @Override
    public void setSendMessagesAllowed(
            ProtocolAccountRef account, String groupJid, boolean enabled) {
        postMode(account, groupJid, SEND_MESSAGES_PATH,
                enabled ? MODE_NOT_ANNOUNCEMENT : MODE_ANNOUNCEMENT);
    }

    /**
     * 设置普通成员是否允许直接添加其他成员。
     *
     * <p>{@code enabled=true} 映射为 all_member_add，false 映射为 admin_add；
     * 该权限不是实际添加成员动作，也不控制邀请链接能力。</p>
     *
     * @param protocolAccountId 协议层账号句柄
     * @param groupJid          WhatsApp 群 JID
     * @param enabled           true 表示所有成员可以直接添加其他成员
     * @throws ProtocolException 当参数缺失或协议调用失败时抛出
     */
    @Override
    public void setAddMembersAllowed(
            ProtocolAccountRef account, String groupJid, boolean enabled) {
        postMode(account, groupJid, ADD_MEMBERS_PATH,
                enabled ? MODE_ALL_MEMBER_ADD : MODE_ADMIN_ADD);
    }

    /**
     * 设置普通成员是否可以访问和分享群邀请链接。
     *
     * <p>该能力与直接添加成员、入群审批都是独立设置，协议层负责使用
     * WhatsApp 的 UpdateGroupProperty MEX mutation 写入 member_link_mode。</p>
     *
     * @param account  协议层执行账号
     * @param groupJid WhatsApp 群 JID
     * @param enabled  true 表示所有成员可访问和分享群邀请链接
     * @throws ProtocolException 当参数缺失或协议调用失败时抛出
     */
    @Override
    public void setInviteViaLinkAllowed(
            ProtocolAccountRef account, String groupJid, boolean enabled) {
        postMode(account, groupJid, INVITE_VIA_LINK_PATH,
                enabled ? MODE_ALL_MEMBER_LINK : MODE_ADMIN_LINK);
    }

    /**
     * 设置新成员入群是否需要管理员批准。
     *
     * <p>{@code enabled=true} 映射为协议 mode {@code on}，false 映射为 {@code off}。</p>
     *
     * @param protocolAccountId 协议层账号句柄
     * @param groupJid          WhatsApp 群 JID
     * @param enabled           true 表示启用管理员批准
     * @throws ProtocolException 当参数缺失或协议调用失败时抛出
     */
    @Override
    public void setJoinApprovalEnabled(
            ProtocolAccountRef account, String groupJid, boolean enabled) {
        postMode(account, groupJid, JOIN_APPROVAL_PATH,
                enabled ? MODE_ON : MODE_OFF);
    }

    /**
     * 按协议层统一 {@code accountId + mode} 结构发送一项群设置。
     *
     * <p>日志只记录设置路径和 mode，不记录协议账号句柄或群 JID。</p>
     *
     * @param protocolAccountId 协议层账号句柄
     * @param groupJid          WhatsApp 群 JID
     * @param suffix            群设置接口路径后缀
     * @param mode              协议层固定 mode
     */
    private void postMode(
            ProtocolAccountRef account,
            String groupJid,
            String suffix,
            String mode) {
        String accountId = requireAccountId(account);
        String jid = requireText(groupJid, "groupJid");
        log.debug("调用协议层修改群设置 settingPath={} mode={}", suffix, mode);
        httpExecutor.postVoid(
                "/v1/groups/%s%s".formatted(jid, suffix),
                new ModeRequest(accountId, mode));
    }

    /**
     * 归一化协议请求必填文本字段。
     *
     * @param value     原始字段值
     * @param fieldName 缺失时用于异常定位的字段名
     * @return 去除首尾空白后的字段值
     * @throws ProtocolException 当字段为空时抛出
     */
    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new ProtocolException(
                    ProtocolErrorCode.BAD_REQUEST,
                    "协议层 group settings 参数缺失 " + fieldName);
        }
        return value.trim();
    }

    private static String requireAccountId(ProtocolAccountRef account) {
        if (account == null) {
            throw new ProtocolException(ProtocolErrorCode.BAD_REQUEST, "协议层操作账号不能为空");
        }
        return requireText(account.protocolAccountId(), "protocolAccountId");
    }

    private record ModeRequest(String accountId, String mode) {
    }
}
