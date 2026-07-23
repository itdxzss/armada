package com.armada.marketing.grouppull.model.vo;

import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;

/** 拉群营销分配阶段读取的账号协议引用。 */
public class GroupPullAccountRefRow {

    private Long accountId;
    private String wsPhone;
    private String protocolId;
    private String protocolAccountId;
    private Integer accountState;
    private Integer loginState;

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public String getWsPhone() {
        return wsPhone;
    }

    public void setWsPhone(String wsPhone) {
        this.wsPhone = wsPhone;
    }

    public String getProtocolId() {
        return protocolId;
    }

    public void setProtocolId(String protocolId) {
        this.protocolId = protocolId;
    }

    public String getProtocolAccountId() {
        return protocolAccountId;
    }

    public void setProtocolAccountId(String protocolAccountId) {
        this.protocolAccountId = protocolAccountId;
    }

    public Integer getAccountState() {
        return accountState;
    }

    public void setAccountState(Integer accountState) {
        this.accountState = accountState;
    }

    public Integer getLoginState() {
        return loginState;
    }

    public void setLoginState(Integer loginState) {
        this.loginState = loginState;
    }

    /** 转为统一协议路由账号引用。 */
    public ProtocolAccountRef protocolRef() {
        return new ProtocolAccountRef(
                accountId,
                ProtocolBackend.fromProtocolId(protocolId),
                protocolAccountId,
                wsPhone);
    }
}
