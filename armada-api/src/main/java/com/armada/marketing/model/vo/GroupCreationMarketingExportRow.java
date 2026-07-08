package com.armada.marketing.model.vo;

/**
 * 建群营销统计导出行。
 *
 * <p>由 Mapper 直接投影生成,供 Excel writer 汇总任务 ID、群名称、建群人数和发送前群成员快照。</p>
 */
public class GroupCreationMarketingExportRow {

    /** 建群营销任务 ID。 */
    private Long taskId;

    /** 建群时使用的群名称。 */
    private String groupSubject;

    /** 料子中的目标成员数量,导出建群人数时会加上群主账号。 */
    private Integer participantCount;

    /** 发送前读取到的群成员数量,导出进群人数时会扣除群主账号。 */
    private Integer sendMemberCount;

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public String getGroupSubject() {
        return groupSubject;
    }

    public void setGroupSubject(String groupSubject) {
        this.groupSubject = groupSubject;
    }

    public Integer getParticipantCount() {
        return participantCount;
    }

    public void setParticipantCount(Integer participantCount) {
        this.participantCount = participantCount;
    }

    public Integer getSendMemberCount() {
        return sendMemberCount;
    }

    public void setSendMemberCount(Integer sendMemberCount) {
        this.sendMemberCount = sendMemberCount;
    }
}
