package com.armada.marketing.model.support;

/**
 * 建群成功后派发营销消息的执行项更新参数。
 *
 * <p>作为 Mapper 参数对象使用,保存群快照、协议摘要、营销发送命令和发送前群人数快照。</p>
 */
public class GroupCreationMarketingItemMarketingDispatch {

    /** 建群营销执行项 ID。 */
    private Long id;

    /** 新建 WhatsApp 群组 JID。 */
    private String groupJid;

    /** 关联的群链接 ID;建群营销新群未导入群库时为空。 */
    private Long groupLinkId;

    /** 关联的普通营销任务 ID;直接走协议 outbox 发送时为空。 */
    private Long marketingTaskId;

    /** 关联的普通营销目标 ID;直接走协议 outbox 发送时为空。 */
    private Long marketingTargetId;

    /** 关联的普通营销发送尝试 ID;直接走协议 outbox 发送时为空。 */
    private Long marketingAttemptId;

    /** 协议层营销消息命令 ID,用于异步结果回写匹配。 */
    private String commandId;

    /** 联系人预保存和建群协议结果摘要 JSON。 */
    private String participantResultJson;

    /** 发送前读取到的群成员数量,用于导出统计。 */
    private Integer sendMemberCount;

    /** 群成员数量读取时间(epoch 毫秒)。 */
    private Long sendMemberCountCheckedAt;

    /** 更新时间(epoch 毫秒)。 */
    private Long updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getGroupJid() {
        return groupJid;
    }

    public void setGroupJid(String groupJid) {
        this.groupJid = groupJid;
    }

    public Long getGroupLinkId() {
        return groupLinkId;
    }

    public void setGroupLinkId(Long groupLinkId) {
        this.groupLinkId = groupLinkId;
    }

    public Long getMarketingTaskId() {
        return marketingTaskId;
    }

    public void setMarketingTaskId(Long marketingTaskId) {
        this.marketingTaskId = marketingTaskId;
    }

    public Long getMarketingTargetId() {
        return marketingTargetId;
    }

    public void setMarketingTargetId(Long marketingTargetId) {
        this.marketingTargetId = marketingTargetId;
    }

    public Long getMarketingAttemptId() {
        return marketingAttemptId;
    }

    public void setMarketingAttemptId(Long marketingAttemptId) {
        this.marketingAttemptId = marketingAttemptId;
    }

    public String getCommandId() {
        return commandId;
    }

    public void setCommandId(String commandId) {
        this.commandId = commandId;
    }

    public String getParticipantResultJson() {
        return participantResultJson;
    }

    public void setParticipantResultJson(String participantResultJson) {
        this.participantResultJson = participantResultJson;
    }

    public Integer getSendMemberCount() {
        return sendMemberCount;
    }

    public void setSendMemberCount(Integer sendMemberCount) {
        this.sendMemberCount = sendMemberCount;
    }

    public Long getSendMemberCountCheckedAt() {
        return sendMemberCountCheckedAt;
    }

    public void setSendMemberCountCheckedAt(Long sendMemberCountCheckedAt) {
        this.sendMemberCountCheckedAt = sendMemberCountCheckedAt;
    }

    public Long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
