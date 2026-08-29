package com.armada.platform.kafka.consumer.contact;

/** 通讯录快照分片的业务处理器。 */
public interface AccountContactsReportedSink {

    /**
     * 落库一个快照分片。
     *
     * @param event 已完成协议字段解析的分片
     */
    void handle(AccountContactsReportedEvent event);
}
