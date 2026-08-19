package com.armada.group.service.impl;

import com.armada.group.mapper.GroupLinkPreviewMapper;
import com.armada.group.model.entity.GroupLinkPreview;
import com.armada.platform.country.model.vo.PhoneLocationReferenceVO;
import com.armada.platform.country.service.CountryService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 把建群人手机号写进退役中的预览表，供群组列表展示创建者与国旗。
 *
 * <p>群组列表的「创建者」与国旗两列仍读 group_link_preview：国旗由手机号区号推导，
 * 两者是同一份数据。新群模型尚未接管这两列，因此这里只做定向兼容写入——只碰创建者相关列，
 * 群名、成员数、邀请码等当前事实一律不回写旧表，避免旧表重新成为事实来源。</p>
 */
@Service
public class GroupCreatorCompatibilityWriter {

    private static final Logger log =
            LoggerFactory.getLogger(GroupCreatorCompatibilityWriter.class);

    private final GroupLinkPreviewMapper previewMapper;
    private final CountryService countryService;

    public GroupCreatorCompatibilityWriter(GroupLinkPreviewMapper previewMapper,
                                           CountryService countryService) {
        this.previewMapper = previewMapper;
        this.countryService = countryService;
    }

    /**
     * 写入创建者手机号与按区号推导的国家信息。
     *
     * <p>推导失败时仍写手机号：创建者本身是有效事实，不该因为认不出国家而整条丢弃，
     * 那样列表连创建者都显示不出来。</p>
     *
     * @param groupLinkId 群入口主键
     * @param creatorPhone 建群人手机号(裸号)
     * @param observedAt  事实观察时间(epoch 毫秒)
     */
    public void writeCreator(long groupLinkId, String creatorPhone, long observedAt) {
        String phone = creatorPhone == null ? null : creatorPhone.trim();
        if (phone == null || phone.isEmpty()) {
            return;
        }
        PhoneLocationReferenceVO location = resolveLocation(phone);
        GroupLinkPreview row = new GroupLinkPreview();
        row.setGroupLinkId(groupLinkId);
        row.setOwnerPhone(phone);
        row.setOwnerPhoneObserved(true);
        row.setCreatorCountryIso2(location == null ? null : location.country().iso2());
        row.setCreatorContinentCode(location == null ? null : location.country().continentCode());
        row.setCreatorPhoneRegionCode(location == null ? null : location.regionCode());
        row.setCreatorPhoneRegionName(location == null ? null : location.regionName());
        // 认不出国家时不宣称观察过国家，避免用空值压过既有的正确国家。
        row.setCreatorCountryObserved(location != null);
        row.setLastPreviewAt(observedAt);
        row.setMetadataObservedAt(observedAt);
        row.setCreatedAt(observedAt);
        row.setUpdatedAt(observedAt);
        previewMapper.upsertCreatorCompatibility(List.of(row));
    }

    private PhoneLocationReferenceVO resolveLocation(String phone) {
        try {
            return countryService.resolveActivePhoneLocations(List.of(phone)).get(phone);
        } catch (RuntimeException e) {
            log.warn("建群人号码归属地推导失败,仅写号码 phoneSuffix={} reason={}",
                    phone.length() <= 4 ? phone : phone.substring(phone.length() - 4),
                    e.getMessage());
            return null;
        }
    }
}
