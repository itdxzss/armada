package com.armada.platform.protocol.model.entity;

/**
 * 协议命令 Outbox 实体,映射 protocol_command_outbox 表一行。
 *
 * <p>一行代表一个账号级协议命令。payload 只保存账号、代理、凭据格式等轻量引用信息;
 * 下线命令不需要代理和凭据字段。不保存完整账号凭据和代理账号密码。</p>
 */
public class ProtocolCommandOutbox {

    /** 主键。 */
    private Long id;

    /** 租户 ID。 */
    private Long tenantId;

    /** 全局唯一命令 ID,用于 Kafka 幂等和排查。 */
    private String commandId;

    /** 批量命令归组 ID;单条命令可为空。 */
    private String batchId;

    /** 命令类型,如 account.online.requested / account.offline.requested。 */
    private String commandType;

    /** 聚合类型,初始固定 ACCOUNT。 */
    private String aggregateType;

    /** 聚合 ID;账号命令对应 account.id。 */
    private Long aggregateId;

    /** 目标 Kafka topic。 */
    private String kafkaTopic;

    /** 目标 Kafka key;同账号命令按 key 保序。 */
    private String kafkaKey;

    /** 协议层账号 ID。 */
    private String protocolAccountId;

    /** 轻量命令 payload(JSON),不包含凭据和代理密码。 */
    private String payloadJson;

    /** 状态码,见 ProtocolCommandOutboxStatus。 */
    private Integer status;

    /** 发布重试次数。 */
    private Integer retryCount;

    /** 下次可重试时间(epoch 毫秒);0=立即可发。 */
    private Long nextRetryAt;

    /** 抢占发送的 publisher 实例。 */
    private String lockedBy;

    /** 抢占发送时间(epoch 毫秒)。 */
    private Long lockedAt;

    /** Kafka producer ack 成功时间(epoch 毫秒)。 */
    private Long sentAt;

    /** 最近一次发布失败原因。 */
    private String lastError;

    /** 创建时间(epoch 毫秒)。 */
    private Long createdAt;

    /** 更新时间(epoch 毫秒)。 */
    private Long updatedAt;

    /** 软删时间(epoch 毫秒);NULL=未删。 */
    private Long deletedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public String getCommandId() {
        return commandId;
    }

    public void setCommandId(String commandId) {
        this.commandId = commandId;
    }

    public String getBatchId() {
        return batchId;
    }

    public void setBatchId(String batchId) {
        this.batchId = batchId;
    }

    public String getCommandType() {
        return commandType;
    }

    public void setCommandType(String commandType) {
        this.commandType = commandType;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public void setAggregateType(String aggregateType) {
        this.aggregateType = aggregateType;
    }

    public Long getAggregateId() {
        return aggregateId;
    }

    public void setAggregateId(Long aggregateId) {
        this.aggregateId = aggregateId;
    }

    public String getKafkaTopic() {
        return kafkaTopic;
    }

    public void setKafkaTopic(String kafkaTopic) {
        this.kafkaTopic = kafkaTopic;
    }

    public String getKafkaKey() {
        return kafkaKey;
    }

    public void setKafkaKey(String kafkaKey) {
        this.kafkaKey = kafkaKey;
    }

    public String getProtocolAccountId() {
        return protocolAccountId;
    }

    public void setProtocolAccountId(String protocolAccountId) {
        this.protocolAccountId = protocolAccountId;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public void setPayloadJson(String payloadJson) {
        this.payloadJson = payloadJson;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }

    public Long getNextRetryAt() {
        return nextRetryAt;
    }

    public void setNextRetryAt(Long nextRetryAt) {
        this.nextRetryAt = nextRetryAt;
    }

    public String getLockedBy() {
        return lockedBy;
    }

    public void setLockedBy(String lockedBy) {
        this.lockedBy = lockedBy;
    }

    public Long getLockedAt() {
        return lockedAt;
    }

    public void setLockedAt(Long lockedAt) {
        this.lockedAt = lockedAt;
    }

    public Long getSentAt() {
        return sentAt;
    }

    public void setSentAt(Long sentAt) {
        this.sentAt = sentAt;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }

    public Long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Long updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Long getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Long deletedAt) {
        this.deletedAt = deletedAt;
    }
}
