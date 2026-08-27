package com.armada.task.service;

import com.armada.task.model.vo.PullTaskGroupAvatarContent;
import com.armada.task.model.vo.PullTaskGroupAvatarUploadVO;
import org.springframework.web.multipart.MultipartFile;

/** 拉群任务群头像本地文件服务。 */
public interface PullTaskGroupAvatarService {

    /** 校验并保存当前租户待绑定的头像。 */
    PullTaskGroupAvatarUploadVO upload(long tenantId, MultipartFile file);

    /** 读取当前租户头像内容。 */
    PullTaskGroupAvatarContent content(long tenantId, String fileKey);

    /**
     * 为当前操作者的新任务预留本人待绑定头像；文件锁持有到事务提交或回滚。
     */
    void reserveForBinding(long tenantId, String fileKey);

    /** 删除当前租户尚未绑定有效任务的头像。 */
    void delete(long tenantId, String fileKey);

    /** 按任务已授权上下文读取头像，供协议执行链使用。 */
    PullTaskGroupAvatarContent contentForTaskExecution(long tenantId, String fileKey);

    /** 任务删除提交后清理其已解除绑定的头像，供任务聚合内部使用。 */
    void deleteAfterTaskRemoval(long tenantId, String fileKey);

    /** 在文件已过期且仍未绑定时删除；供本地文件清理任务调用。 */
    boolean deleteExpiredUnbound(long tenantId, String fileKey, long cutoffEpochMillis);
}
