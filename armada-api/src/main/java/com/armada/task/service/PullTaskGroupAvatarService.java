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

    /** 校验头像文件属于当前租户且真实存在。 */
    void requireStored(long tenantId, String fileKey);

    /** 校验头像属于当前租户、真实存在且尚未绑定有效任务。 */
    void requireUnbound(long tenantId, String fileKey);

    /**
     * 为当前事务预留待绑定头像；文件锁持有到事务提交或回滚，避免并发删除。
     */
    void reserveForBinding(long tenantId, String fileKey);

    /** 删除当前租户尚未绑定有效任务的头像。 */
    void delete(long tenantId, String fileKey);

    /** 在文件已过期且仍未绑定时删除；供本地文件清理任务调用。 */
    boolean deleteExpiredUnbound(long tenantId, String fileKey, long cutoffEpochMillis);
}
