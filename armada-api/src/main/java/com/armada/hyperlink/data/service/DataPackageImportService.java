package com.armada.hyperlink.data.service;

import com.armada.hyperlink.data.model.enums.DataPackageImportMode;
import com.armada.hyperlink.data.model.vo.DataPackageImportResultVO;
import org.springframework.web.multipart.MultipartFile;

/** 数据包 TXT 追加和覆盖导入服务。 */
public interface DataPackageImportService {

    /**
     * 创建独立 PROCESSING 审计，再在包行锁事务内执行导入。
     *
     * @param dataPackageId 数据包 ID
     * @param mode 导入模式
     * @param file UTF-8 TXT
     * @param createdBy 操作人，可空
     * @return 导入计数与最终代次
     */
    DataPackageImportResultVO importPhones(
            Long dataPackageId,
            DataPackageImportMode mode,
            MultipartFile file,
            Long createdBy);
}
