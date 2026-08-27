package com.armada.hyperlink.data.model.dto;

import com.armada.shared.paging.PageQuery;

/** 当前代号码明细分页查询。 */
public class DataPackagePhoneQuery extends PageQuery {

    /** 号码模糊筛选。 */
    private String phone;
    /** 对外池状态枚举名。 */
    private String poolStatus;
    /** 国家精确筛选，UNKNOWN 表示未识别国家。 */
    private String countryIso2;
    /** Service 注入的目标数据包 ID。 */
    private Long dataPackageId;
    /** Service 注入的当前代次。 */
    private Integer generation;
    /** Service 解析后的池状态码。 */
    private Integer poolStatusCode;

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getPoolStatus() { return poolStatus; }
    public void setPoolStatus(String poolStatus) { this.poolStatus = poolStatus; }
    public String getCountryIso2() { return countryIso2; }
    public void setCountryIso2(String countryIso2) { this.countryIso2 = countryIso2; }
    public Long getDataPackageId() { return dataPackageId; }
    public void setDataPackageId(Long dataPackageId) { this.dataPackageId = dataPackageId; }
    public Integer getGeneration() { return generation; }
    public void setGeneration(Integer generation) { this.generation = generation; }
    public Integer getPoolStatusCode() { return poolStatusCode; }
    public void setPoolStatusCode(Integer poolStatusCode) { this.poolStatusCode = poolStatusCode; }
}
