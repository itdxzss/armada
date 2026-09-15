package com.armada.resource.model.dto;

import com.armada.shared.paging.PageQuery;

/** 拉群数据包PhoneQuery数据库/查询数据。 */
public class GroupDataPackagePhoneQuery extends PageQuery {
    /** 号码关键词。 */
    private String phone;
    /** 状态名。 */
    private String status;
    /** 内部状态码。 */
    private Integer statusCode;
    /** 读取号码关键词。 */
    public String getPhone() { return phone; }
    /** 设置号码关键词。 */
    public void setPhone(String value) { phone = value; }
    /** 读取状态名。 */
    public String getStatus() { return status; }
    /** 设置状态名。 */
    public void setStatus(String value) { status = value; }
    /** 读取内部状态码。 */
    public Integer getStatusCode() { return statusCode; }
    /** 设置内部状态码。 */
    public void setStatusCode(Integer value) { statusCode = value; }
}
