package com.armada.admin.model.dto;

import java.util.List;

/** 修改系统用户参数；登录用户名创建后不可修改。 */
public record UserUpdateDTO(String nickname, List<Long> roleIds) {
}
