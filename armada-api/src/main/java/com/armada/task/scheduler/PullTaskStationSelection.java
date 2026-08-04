package com.armada.task.scheduler;

import com.armada.task.model.entity.PullTaskGroupAccount;
import java.util.List;

/**
 * 一次拉人调用的站台选择结果。
 *
 * @param stations    已绑定到调用的站台角色行
 * @param missingCount 资源不足时的缺口数
 */
public record PullTaskStationSelection(
        List<PullTaskGroupAccount> stations,
        int missingCount) {

    /** 复制集合，避免事务外修改选择结果。 */
    public PullTaskStationSelection {
        stations = stations == null ? List.of() : List.copyOf(stations);
        if (missingCount < 0) {
            throw new IllegalArgumentException("missingCount 不能为负数");
        }
    }

    /** @return 是否取得配置要求的完整站台数量 */
    public boolean sufficient() {
        return missingCount == 0;
    }
}
