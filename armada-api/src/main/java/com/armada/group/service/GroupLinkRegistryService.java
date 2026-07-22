package com.armada.group.service;

import com.armada.shared.exception.BusinessException;
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

    /**
     * 登记账号快照或精确事件观察到的群，并返回统一群组池 ID。
     *
     * <p>方法优先按群 JID 复用现有入口，包括已软删除的历史入口；匹配历史入口时会复活原记录并更新群名，
     * 避免同一 WhatsApp 群产生多个 {@code group_link}。找不到历史入口时才创建 {@code wa://group/} 记录。
     * 本方法只维护本地群组池，不调用协议层。</p>
     *
     * @param groupJid WhatsApp 群 JID，不能为空
     * @param groupName 协议层观察到的群名称，可空；非空时最多保留 128 个字符
     * @param now 本地登记或复活时间（epoch 毫秒）
     * @return 复用、复活或新建后的 {@code group_link.id}
     * @throws BusinessException 当群 JID 为空时抛出
     */
    Long registerAccountObservedGroup(String groupJid, String groupName, long now);

    Long registerSelfBuiltGroup(String groupJid,
                                String groupName,
                                Long ownerAccountId,
                                String ownerPhone,
                                Integer memberCount,
                                long now);
}
