package com.armada.hyperlink.data.service;

import com.armada.hyperlink.data.model.dto.DataPackageCreateDTO;
import com.armada.hyperlink.data.model.dto.DataPackagePhoneQuery;
import com.armada.hyperlink.data.model.dto.DataPackageQuery;
import com.armada.hyperlink.data.model.dto.DataPackageUpdateDTO;
import com.armada.hyperlink.data.model.enums.DataPackageUsageStatus;
import com.armada.hyperlink.data.model.enums.DataPackageClickExportFormat;
import com.armada.hyperlink.data.model.vo.DataPackageCountryOptionVO;
import com.armada.hyperlink.data.model.vo.DataPackageDetailVO;
import com.armada.hyperlink.data.model.vo.DataPackageExportFile;
import com.armada.hyperlink.data.model.vo.DataPackageListItemVO;
import com.armada.hyperlink.data.model.vo.DataPackagePhoneItemVO;
import com.armada.shared.response.PageResult;
import java.util.List;

/** 超链数据包 CRUD、列表、国家候选和号码分页服务。 */
public interface DataPackageService {

    /** 查询有效数据包分页或未来任务候选。 */
    PageResult<DataPackageListItemVO> list(DataPackageQuery query);

    /** 查询当前租户有效数据包详情。 */
    DataPackageDetailVO detail(Long id);

    /** 创建空数据包和一对一零值统计行。 */
    DataPackageDetailVO create(DataPackageCreateDTO request, Long createdBy);

    /** 按版本完整更新名称和备注。 */
    DataPackageDetailVO update(Long id, DataPackageUpdateDTO request);

    /** 软删除数据包并记录操作人，号码由保留期任务后续清理。 */
    void delete(Long id, Long deletedBy);

    /** 查询父包当前 generation 的号码明细。 */
    PageResult<DataPackagePhoneItemVO> phones(Long id, DataPackagePhoneQuery query);

    /** 把当前代可重试失败号码恢复为未使用，未注册号码保持失败。 */
    int resetFailed(Long id);

    /** 按竞品状态口径导出当前代手机号 TXT。 */
    DataPackageExportFile exportPhones(Long id, DataPackageUsageStatus usageStatus);

    /** 按竞品状态口径批量导出多个数据包当前代手机号 TXT。 */
    DataPackageExportFile exportPhones(List<Long> ids, DataPackageUsageStatus usageStatus);

    /** 批量导出所选数据包的点击记录；TXT 仅手机号，CSV 包含点击上下文。 */
    DataPackageExportFile exportClickRecords(
            List<Long> ids, DataPackageClickExportFormat format);

    /** 读取启用国家主数据并追加 UNKNOWN 固定候选。 */
    List<DataPackageCountryOptionVO> countries();
}
