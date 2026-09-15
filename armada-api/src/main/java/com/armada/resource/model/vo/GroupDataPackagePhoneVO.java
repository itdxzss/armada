package com.armada.resource.model.vo;

/** 拉群数据包接口PhoneVO。 */
public record GroupDataPackagePhoneVO(Long id, String phone, Boolean adminRequired, Integer memberSeq, Integer sourceLineNo, String countryIso2, String status, Long createdAt) { }
