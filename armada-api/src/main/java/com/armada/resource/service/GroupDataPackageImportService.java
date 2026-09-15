package com.armada.resource.service;

import com.armada.resource.model.dto.GroupDataPackageQuery;
import com.armada.resource.model.vo.GroupDataPackageImportResultVO;
import com.armada.resource.model.vo.GroupDataPackageImportVO;
import com.armada.shared.response.PageResult;
import org.springframework.web.multipart.MultipartFile;

/** 拉群数据包导入编排及导入记录。 */
public interface GroupDataPackageImportService {
    /** 校验文件、原子导入及失败审计。 */
    GroupDataPackageImportResultVO importPhones(ImportRequest request);
    /** 当前租户包的导入记录分页。 */
    PageResult<GroupDataPackageImportVO> imports(long id, GroupDataPackageQuery query);
    /** HTTP输入及可信操作人。 */
    record ImportRequest(long packageId, String mode, MultipartFile file, int privacyFilterDays, long userId) { }
}
