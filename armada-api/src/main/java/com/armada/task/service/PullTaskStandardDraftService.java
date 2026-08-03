package com.armada.task.service;

import com.armada.shared.exception.BusinessException;
import com.armada.task.model.vo.PullTaskStandardDraftVO;

/**
 * 普通群链接创建页的草稿编排服务。
 *
 * <p>每个用户同一时刻只保留一条 {@code STANDARD} 草稿（ADR-0007）。草稿不进任务列表、
 * 任务看板与任何聚合统计，只在创建页可见。</p>
 */
public interface PullTaskStandardDraftService {

    /**
     * 回读当前用户的草稿。
     *
     * @param userId 当前登录用户 ID
     * @return 草稿视图；用户还没有草稿时 {@code draftTaskId} 为 null，各列表为空
     */
    PullTaskStandardDraftVO current(long userId);

    /**
     * 移除草稿中的单条执行行，链接与 TXT 一并丢弃、不回匹配池。
     *
     * @param rowId  执行行 ID
     * @param userId 当前登录用户 ID
     * @return 移除后的草稿视图
     * @throws BusinessException 草稿不存在，或执行行不属于该草稿、已冻结时
     */
    PullTaskStandardDraftVO removeRow(long rowId, long userId);

    /**
     * 清空草稿中的全部执行行与料子，保留草稿任务行本身以供复用。
     *
     * @param userId 当前登录用户 ID
     * @return 清空后的草稿视图
     * @throws BusinessException 草稿不存在时
     */
    PullTaskStandardDraftVO clear(long userId);
}
