package com.armada.admin.model.vo;

import java.util.List;

/** 系统用户展示对象；禁止包含任何密码字段。 */
public record UserVO(
        Long id,
        String username,
        String nickname,
        Integer status,
        List<Long> roleIds,
        Long createdAt,
        Long updatedAt) {
}
