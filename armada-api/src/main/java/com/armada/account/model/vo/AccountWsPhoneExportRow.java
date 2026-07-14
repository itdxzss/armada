package com.armada.account.model.vo;

/** WS 号码导出查询的最小投影。 */
public class AccountWsPhoneExportRow {
    private Long id;
    private String wsPhone;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getWsPhone() { return wsPhone; }
    public void setWsPhone(String wsPhone) { this.wsPhone = wsPhone; }
}
