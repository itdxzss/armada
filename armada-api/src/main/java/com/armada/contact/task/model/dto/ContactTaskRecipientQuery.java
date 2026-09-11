package com.armada.contact.task.model.dto;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.paging.PageQuery;
import java.util.Set;

/** 任务级收件人查询，所有筛选和分页均下推 SQL。 */
public class ContactTaskRecipientQuery extends PageQuery {
    private static final Set<String> SEND_STATUSES = Set.of(
            "PENDING", "SENDING", "SUCCESS", "FAILED", "UNKNOWN", "SKIPPED");
    private static final Set<String> RECEIPT_STATUSES = Set.of(
            "CONFIRMED", "SINGLE_ONLY", "DELIVERED", "DELIVERED_UNREAD", "READ");
    private Long taskAccountId;
    private String sendStatus;
    private String receiptStatus;
    private String errorCode;

    /** 默认每页 20 条，与原账号级接口一致。 */
    public ContactTaskRecipientQuery() { setPageSize(20); }
    public Long getTaskAccountId() { return taskAccountId; }
    public void setTaskAccountId(Long value) { taskAccountId = value; }
    public String getSendStatus() { return sendStatus; }
    public void setSendStatus(String value) { sendStatus = clean(value); }
    public String getReceiptStatus() { return receiptStatus; }
    public void setReceiptStatus(String value) { receiptStatus = clean(value); }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String value) { errorCode = clean(value); }
    @Override public void setPageSize(int value) { super.setPageSize(value <= 0 ? 20 : Math.min(value, 200)); }
    /** 使用 long 计算偏移，避免大页码整数溢出。 */
    public long getRowOffset() { return ((long) getPage() - 1) * getPageSize(); }

    /** 非法筛选显式拒绝，不静默退化成无筛选查询。 */
    public void validate() {
        if ((taskAccountId != null && taskAccountId <= 0)
                || (sendStatus != null && !SEND_STATUSES.contains(sendStatus))
                || (receiptStatus != null && !RECEIPT_STATUSES.contains(receiptStatus))
                || (errorCode != null && errorCode.length() > 64)) {
            throw new BusinessException(ErrorCode.VALIDATION, "通讯录明细筛选条件非法");
        }
    }

    private static String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
