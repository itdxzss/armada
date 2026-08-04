package com.armada.task.scheduler;

import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import java.util.List;

/**
 * 一次调用预留的站台协议账号。
 *
 * @param accounts     已通过实时校验的候选；不足时保留部分结果但调用方不得绑定
 * @param missingCount 不足时的缺口数
 */
public record PullTaskStationCandidates(
        List<ProtocolAccountRef> accounts,
        int missingCount) {

    /** 固化候选顺序。 */
    public PullTaskStationCandidates {
        accounts = accounts == null ? List.of() : List.copyOf(accounts);
        if (missingCount < 0) {
            throw new IllegalArgumentException("missingCount 不能为负数");
        }
    }

    /** @return 是否取得配置要求的完整站台数量 */
    public boolean sufficient() {
        return missingCount == 0;
    }
}
