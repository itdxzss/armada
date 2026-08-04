package com.armada.task.service;

import com.armada.shared.exception.BusinessException;
import com.armada.task.model.dto.PullTaskStandardCreateDTO;
import com.armada.task.model.vo.PullTaskStandardCreatedVO;

/** 普通群链接任务的提交冻结服务。 */
public interface PullTaskStandardCreateService {

    /**
     * 把草稿冻结为待启动任务。
     *
     * <p>单事务内写冻结配置、回填群入口 ID、把执行行推进为待启动、把任务推进为
     * {@code WAIT_START}。不重新随机，落库计划与用户在创建页看到的完全一致。
     * 重复提交返回既有任务而不是报错。</p>
     *
     * @param request 提交入参
     * @param userId  当前登录用户 ID
     * @return 创建完成的任务行
     * @throws BusinessException 草稿不存在或不属于当前用户、无执行行、配置非法、
     *                           分组不存在，或任一链接已被其他任务占用时
     */
    PullTaskStandardCreatedVO create(PullTaskStandardCreateDTO request, long userId);
}
