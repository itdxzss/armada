package com.armada.group.service;

import com.armada.platform.protocol.model.enums.ProtocolBackend;
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
     * @param observedBackend 本次观察群的协议后端
     * @param now 本地登记或复活时间（epoch 毫秒）
     * @return 复用、复活或新建后的 {@code group_link.id}
     * @throws BusinessException 当群 JID 为空时抛出
     */
    Long registerAccountObservedGroup(String groupJid,
                                      String groupName,
                                      ProtocolBackend observedBackend,
                                      long now);

    /**
     * 登记业务流程刚创建成功的自建群及建群账号在群关系。
     *
     * @param groupJid WhatsApp 群 JID，不能为空
     * @param groupName 建群成功时取得的群名称，可空
     * @param ownerAccountId 建群账号的 Armada 账号 ID，不能为空
     * @param ownerPhone 建群账号手机号，可空
     * @param memberCount 建群完成时取得的群成员数，可空
     * @param now 登记时间（epoch 毫秒）
     * @return 复用或新建后的 {@code group_link.id}
     * @throws BusinessException 当群 JID 或建群账号 ID 缺失时抛出
     */
    Long registerSelfBuiltGroup(String groupJid,
                                String groupName,
                                Long ownerAccountId,
                                String ownerPhone,
                                Integer memberCount,
                                long now);

    /**
     * 登记拉群流程已经确认的账号在群关系。
     *
     * @param groupLinkId 统一群入口 ID，不能为空
     * @param groupJid WhatsApp 群 JID，不能为空
     * @param accountId 已确认进群的 Armada 账号 ID，不能为空
     * @param admin 该账号是否已确认为群管理员
     * @param now 关系确认时间（epoch 毫秒）
     * @throws BusinessException 当群入口、群 JID 或账号 ID 缺失时抛出
     */
    void registerKnownMembership(Long groupLinkId,
                                 String groupJid,
                                 Long accountId,
                                 boolean admin,
                                 long now);
}
