package com.armada.hyperlink.task.model.vo;

/** 国家筛选选项；value 固定为 ISO2，附带真实国旗和洲代码元数据。 */
public record HyperlinkCountryOptionVO(
        String value,
        String label,
        String flag,
        String continentCode) { }
