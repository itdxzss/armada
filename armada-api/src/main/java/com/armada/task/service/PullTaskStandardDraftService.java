package com.armada.task.service;

import com.armada.shared.exception.BusinessException;
import com.armada.task.model.enums.PullTaskCreationMode;
import com.armada.task.model.vo.PullTaskStandardDraftVO;
import java.util.List;
import org.springframework.web.multipart.MultipartFile;

/**
 * 普通群链接创建页的草稿编排服务。
 *
 * <p>每个用户同一时刻只保留一条 {@code STANDARD} 草稿（ADR-0007）。草稿不进任务列表、
 * 任务看板与任何聚合统计，只在创建页可见。</p>
 */
public interface PullTaskStandardDraftService {

    /**
     * 解析上传的 TXT，并按每个有效文件增量追加一条草稿执行行。
     *
     * <p>拉人模式的分组在最终提交时冻结，具体群组在运行时领取；为兼容旧前端，
     * {@code groupFolderId} 与 {@code linksText} 参数暂时保留，但草稿阶段不读取。</p>
     *
     * @param groupFolderId 群组列表运营分组 ID，允许为空
     * @param linksText    创建页链接框的全量文本，允许为空
     * @param files        本次新增的 .txt 料子文件，允许为空
     * @param userId       当前登录用户 ID
     * @param operatorName 操作员展示名快照，建草稿时写入
     * @return 追加后的完整草稿视图
     * @throws BusinessException 文件数、大小、扩展名或内容不合法时
     */
    PullTaskStandardDraftVO plan(PullTaskCreationMode creationMode,
                                 Long groupFolderId,
                                 String linksText,
                                 List<MultipartFile> files,
                                 long userId,
                                 String operatorName);

    /**
     * 回读当前用户的草稿。
     *
     * @param userId 当前登录用户 ID
     * @return 草稿视图；用户还没有草稿时 {@code draftTaskId} 为 null，各列表为空
     */
    PullTaskStandardDraftVO current(long userId);

    /**
     * 移除草稿中的单条 TXT 执行行。
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
