package com.armada.hyperlink.data.service;

/** 数据包内部统计校准、导入超时恢复与号码保留期清理服务。 */
public interface DataPackageMaintenanceService {

    /** 按当前租户重算指定包当前代统计；不暴露 HTTP 接口。 */
    void reconcile(Long dataPackageId);

    /** 独立事务硬删一批退役或软删包过期号码，最多 2000 行。 */
    int purgeExpiredPhoneBatch();

    /** 独立事务把一批超时 PROCESSING 导入收敛为 FAILED。 */
    int recoverStaleImportBatch();
}
