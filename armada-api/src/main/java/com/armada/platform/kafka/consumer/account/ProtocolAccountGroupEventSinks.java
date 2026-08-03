package com.armada.platform.kafka.consumer.account;

import org.springframework.stereotype.Component;

/** 聚合账号自身群关系与普通群成员进退群事实的下游处理入口。 */
@Component
public class ProtocolAccountGroupEventSinks {

    private final ProtocolAccountGroupMembershipChangedSink membershipChangedSink;
    private final ProtocolGroupDepartureSink groupDepartureSink;
    private final ProtocolGroupJoinSink groupJoinSink;

    public ProtocolAccountGroupEventSinks(
            ProtocolAccountGroupMembershipChangedSink membershipChangedSink,
            ProtocolGroupDepartureSink groupDepartureSink,
            ProtocolGroupJoinSink groupJoinSink) {
        this.membershipChangedSink = membershipChangedSink;
        this.groupDepartureSink = groupDepartureSink;
        this.groupJoinSink = groupJoinSink;
    }

    /** 分发账号自身群关系变化。 */
    public void handleMembershipChanged(ProtocolAccountGroupMembershipChangedEvent event) {
        membershipChangedSink.handleMembershipChanged(event);
    }

    /** 分发普通群成员退群事实。 */
    public void handleDepartures(ProtocolGroupDepartureEvent event) {
        groupDepartureSink.handleDepartures(event);
    }

    /** 分发普通群成员进群事实。 */
    public void handleJoins(ProtocolGroupJoinEvent event) {
        groupJoinSink.handleJoins(event);
    }
}
