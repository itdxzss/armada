-- 第二阶段用户文件切片：拉群任务本地群头像增加独立用户归属元数据。
-- 历史磁盘文件没有可靠创建人，不猜测归属；普通用户不可直接访问，租户管理员与任务执行链兼容读取。

CREATE TABLE IF NOT EXISTS pull_task_group_avatar_file (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '拉群任务群头像文件元数据主键',
    tenant_id BIGINT NOT NULL COMMENT '所属租户ID',
    file_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '租户头像目录内安全随机文件Key',
    owner_user_id BIGINT NOT NULL COMMENT '归属用户ID;新上传必须来自可信登录身份',
    created_at BIGINT NOT NULL COMMENT '上传时间(epoch毫秒)',
    PRIMARY KEY (id),
    UNIQUE KEY uq_pull_task_group_avatar_file_key (tenant_id, file_key),
    KEY idx_pull_task_group_avatar_file_owner (tenant_id, owner_user_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='拉群任务本地群头像文件归属元数据';
