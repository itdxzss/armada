package com.armada.account.service;

import com.armada.account.model.dto.AccountHyperlinkCandidateQuery;
import com.armada.account.model.vo.AccountHyperlinkCandidateVO;
import java.util.List;

/** 账号域向超链任务提供的候选查询与账号派发串行边界。 */
public interface AccountHyperlinkCandidateService {

    /**
     * 在当前租户内按完整、已支持的筛选条件查询发信候选。
     *
     * @param query 已由任务域白名单归一化的查询条件
     * @param afterPriority 上一页末行的账号优先级；首页为空
     * @param afterAccountId 上一页末行的账号 ID；首页为空
     * @param limit 最大返回数
     * @return 按优先级和账号 ID 稳定排序的候选快照
     */
    List<AccountHyperlinkCandidateVO> selectCandidates(
            AccountHyperlinkCandidateQuery query, Integer afterPriority,
            Long afterAccountId, int limit);

    /**
     * 按与候选 select 完全相同的条件在数据库直接统计当前租户匹配账号数。
     *
     * @param query 已由任务域白名单归一化的查询条件
     * @return 匹配账号数
     */
    int countCandidates(AccountHyperlinkCandidateQuery query);

    /**
     * 统计当前租户已经配置账号的 PRIVATE 协议节点总数，不随账号范围筛选变化。
     *
     * @param privateCapableBackends 已通过超链 PRIVATE 门禁的协议后端
     * @return 按协议地址去重的节点数
     */
    int countProtocols(List<String> privateCapableBackends);

    /** 当前租户正常账号真实协议 ID 选项；去重并稳定排序。 */
    List<String> listProtocolIds(List<String> privateCapableBackends);

    /**
     * 在当前事务内锁定当前租户的账号身份行，串行化同账号跨任务派发。
     *
     * @param accountId 账号 ID
     * @return true 表示账号仍可发送消息且已锁定；false 表示账号不存在、已软删或消息发送受限
     */
    boolean lockForHyperlinkDispatch(long accountId);
}
