package com.armada.group.model.vo;

import java.util.List;

/**
 * 群链接导入前预检测汇总。
 *
 * @param total       参与预检测的非空行数
 * @param available   可用数量
 * @param unavailable 不可用数量
 * @param items       每行预检测结果
 */
public record GroupLinkPrecheckResultVO(
        int total,
        int available,
        int unavailable,
        List<GroupLinkPrecheckItemVO> items) {
}
