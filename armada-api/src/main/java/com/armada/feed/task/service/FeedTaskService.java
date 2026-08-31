package com.armada.feed.task.service;

import com.armada.feed.task.model.dto.FeedTaskFormDTO;
import com.armada.feed.task.model.dto.FeedTaskQuery;
import com.armada.feed.task.model.vo.FeedTaskAccountVO;
import com.armada.feed.task.model.vo.FeedTaskVO;
import com.armada.shared.response.PageResult;
import org.springframework.web.multipart.MultipartFile;

/** 动态发布任务业务服务。 */
public interface FeedTaskService {

    /** 分页查询当前租户动态发布任务。 */
    PageResult<FeedTaskVO> list(FeedTaskQuery query);

    /** 查询任务详情。 */
    FeedTaskVO detail(Long id);

    /** 创建动态发布任务。 */
    FeedTaskVO create(FeedTaskFormDTO form, MultipartFile linkPreviewImage, Long createdBy);

    /** 编辑未开始的动态发布任务。 */
    FeedTaskVO update(Long id, FeedTaskFormDTO form, MultipartFile linkPreviewImage);

    /** 执行任务动作。 */
    FeedTaskVO action(Long id, String action);

    /** 分页查询账号发布明细。 */
    PageResult<FeedTaskAccountVO> accountData(Long id, String accountPhone, Integer page, Integer pageSize);
}
