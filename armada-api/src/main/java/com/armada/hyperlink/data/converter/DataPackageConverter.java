package com.armada.hyperlink.data.converter;

import com.armada.hyperlink.data.model.entity.DataPackagePhone;
import com.armada.hyperlink.data.model.enums.DataPackagePoolStatus;
import com.armada.hyperlink.data.model.vo.DataPackageDetailVO;
import com.armada.hyperlink.data.model.vo.DataPackageListItemVO;
import com.armada.hyperlink.data.model.vo.DataPackageListRow;
import com.armada.hyperlink.data.model.vo.DataPackageMetricsVO;
import com.armada.hyperlink.data.model.vo.DataPackagePhoneItemVO;
import java.util.List;
import org.mapstruct.Mapper;

/** 数据包 Mapper 投影到稳定 API VO 的转换器。 */
@Mapper(componentModel = "spring")
public interface DataPackageConverter {

    /** 把一对一统计投影转换为列表项。 */
    default DataPackageListItemVO toListItem(
            DataPackageListRow row,
            List<String> countries,
            String primaryCountryIso2) {
        return new DataPackageListItemVO(
                row.getId(), row.getName(), row.getRemark(), countries, primaryCountryIso2, metrics(row),
                value(row.getVersion()), value(row.getCreatedAt()), value(row.getUpdatedAt()));
    }

    /** 把一对一统计投影转换为详情。 */
    default DataPackageDetailVO toDetail(
            DataPackageListRow row,
            List<String> countries,
            String primaryCountryIso2) {
        return new DataPackageDetailVO(
                row.getId(), row.getName(), row.getRemark(), countries, primaryCountryIso2, metrics(row),
                value(row.getVersion()), value(row.getCreatedAt()), value(row.getUpdatedAt()),
                value(row.getCurrentGeneration()));
    }

    /** 把号码实体转换为当前代分页出参。 */
    default DataPackagePhoneItemVO toPhoneItem(DataPackagePhone phone) {
        return new DataPackagePhoneItemVO(
                phone.getId(), value(phone.getGeneration()), phone.getPhone(),
                phone.getCountryIso2(), DataPackagePoolStatus.fromCode(phone.getPoolStatus()),
                phone.getSourceImportId(), value(phone.getCreatedAt()));
    }

    private static DataPackageMetricsVO metrics(DataPackageListRow row) {
        int total = value(row.getPhoneCount());
        int unused = value(row.getUnusedCount());
        int unregistered = value(row.getUnregisteredCount());
        return new DataPackageMetricsVO(
                total,
                unused,
                Math.max(0, total - unused),
                value(row.getSentCount()),
                value(row.getDeliveredCount()),
                value(row.getRetryableFailedCount()) + unregistered,
                unregistered,
                0);
    }

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }

    private static long value(Long value) {
        return value == null ? 0L : value;
    }
}
