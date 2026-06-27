package com.armada.group.service;

import java.util.List;

/**
 * 群组池内部登记服务。
 */
public interface GroupLinkRegistryService {

    /**
     * 将进群任务里的有效群邀请链接登记为群组池目标。
     *
     * <p>该方法只做本地 group_link 落库/复活,不调用协议层;格式不合格的链接静默忽略,
     * 由进群任务明细继续记录自身的无效链接行。</p>
     *
     * @param rawLinks 进群任务输入中的候选群链接
     */
    void registerJoinTaskTargets(List<String> rawLinks);
}
