package com.armada.platform.country.model.vo;

import java.util.List;

/** 国家下拉选项列表包装,匹配前端 data.rows 读取方式。 */
public record CountryOptionsVO(List<CountryOptionVO> rows) {
}
