package com.armada.account.mapper;

import com.armada.account.model.dto.AccountExportCandidate;
import com.armada.account.model.dto.AccountExportMaterial;
import com.armada.account.model.entity.AccountExportJob;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 账号导出事务查询；所有表由租户插件隔离，调用方另校验操作人。 */
@Mapper
public interface AccountExportMapper {
    /** 无锁读取所选账号快照；最终正确性由条件更新和影响行数保证。 */
    List<AccountExportCandidate> candidates(@Param("ids") List<Long> ids);
    /** 创建时无锁查找幂等记录，避免文件生成期间持有任何读锁。 */
    AccountExportJob findJob(@Param("id") String id);
    /** 仅提取所选账号成功导入的原文，不按手机号或整批扩大范围。 */
    List<AccountExportMaterial> materials(@Param("ids") List<Long> ids);
    /** 保存文件及交付元数据。 */
    int insertJob(AccountExportJob job);
    /** 保存预占前业务状态，供取消时恢复。 */
    int insertItems(@Param("jobId") String jobId, @Param("rows") List<AccountExportCandidate> rows);
    /** 产物落库后预占为导出状态，仅离线账号可命中。 */
    int reserve(@Param("rows") List<AccountExportCandidate> rows, @Param("now") long now);
    /** 锁定作业；跨租户会被插件过滤。 */
    AccountExportJob lockJob(@Param("id") String id);
    /** 读取当前用户最近记录，不加载敏感 ZIP。 */
    List<AccountExportJob> listJobs(@Param("userId") long userId);
    /** 本用户到期未交付作业，用于安全取消并释放预占。 */
    List<String> expiredReady(@Param("userId") long userId, @Param("now") long now);
    /** 已过期文件清理，不清除历史账号审计。 */
    int purgeArchives(@Param("userId") long userId, @Param("now") long now);
    /** 单个到期产物删除；内部维护在恢复租户上下文后调用。 */
    int purgeArchive(@Param("id") String id, @Param("now") long now);
    /** 取消只恢复仍由此导出预占的账号，保持离线。 */
    int restore(@Param("id") String id, @Param("now") long now);
    /** 交付后条件删除账号；仍须离线、导出预占、归属不变且未被任务占用。 */
    int removeAccounts(@Param("rows") List<AccountExportCandidate> rows, @Param("now") long now);
    /** 控端运行凭据同时失活，保留历史导入材料。 */
    int removeCredentials(@Param("ids") List<Long> ids, @Param("now") long now);
    /** 幂等交付终态。 */
    int complete(@Param("id") String id, @Param("now") long now);
    /** 取消并清除本次临时 ZIP。 */
    int cancel(@Param("id") String id);
}
