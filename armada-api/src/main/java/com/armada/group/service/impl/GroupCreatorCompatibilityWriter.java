package com.armada.group.service.impl;

import com.armada.group.mapper.GroupLinkPreviewMapper;
import com.armada.group.model.entity.GroupLinkPreview;
import com.armada.group.model.enums.GroupCreatorPhoneSource;
import com.armada.platform.country.model.vo.CountryReferenceVO;
import com.armada.platform.country.service.CountryService;
import com.armada.platform.protocol.util.GroupCreatorPhones;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 把建群人手机号写进退役中的预览表，供群组列表展示创建者与国旗。
 *
 * <p>群组列表的创建者国家与洲仍读 group_link_preview：国家由所选国际手机号严格解析，来源单独保存，
 * 洲来自国家主数据。新群模型尚未接管这些列，因此这里只做定向兼容写入——只碰创建者相关列，
 * 群名、成员数、邀请码等当前事实一律不回写旧表，避免旧表重新成为事实来源。</p>
 */
@Service
public class GroupCreatorCompatibilityWriter {

    private static final Logger log =
            LoggerFactory.getLogger(GroupCreatorCompatibilityWriter.class);

    private static final Pattern LEGACY_GROUP_JID = Pattern.compile("^([0-9]+)-([0-9]+)@g\\.us$");

    private final GroupLinkPreviewMapper previewMapper;
    private final CountryService countryService;

    public GroupCreatorCompatibilityWriter(GroupLinkPreviewMapper previewMapper,
                                           CountryService countryService) {
        this.previewMapper = previewMapper;
        this.countryService = countryService;
    }

    /** 所有群资料入口共用：协议号码优先，缺失时使用老格式群 JID。 */
    public void writeCreator(long groupLinkId, String groupJid, String creatorPhone, long observedAt) {
        GroupLinkPreview row = new GroupLinkPreview();
        row.setGroupLinkId(groupLinkId);
        row.setGroupJid(groupJid);
        row.setOwnerPhone(creatorPhone);
        row.setLastPreviewAt(observedAt);
        row.setMetadataObservedAt(observedAt);
        row.setCreatedAt(observedAt);
        row.setUpdatedAt(observedAt);
        writeCreators(List.of(row));
    }

    /** 保留账号同步的批量写入，号码与地区只在这里推导。 */
    public void writeCreators(List<GroupLinkPreview> rows) {
        prepareCreators(rows);
        List<GroupLinkPreview> resolved = rows.stream()
                .filter(row -> Boolean.TRUE.equals(row.getOwnerPhoneObserved())).toList();
        if (!resolved.isEmpty()) {
            previewMapper.upsertCreatorCompatibility(resolved);
        }
    }

    /** 完整快照先准备字段，随后由原快照事务统一落库。 */
    public void prepareCreators(List<GroupLinkPreview> rows) {
        for (GroupLinkPreview row : rows) {
            String phone = GroupCreatorPhones.phone(row.getOwnerPhone());
            int source = Integer.valueOf(GroupCreatorPhoneSource.JID_DERIVED.code()).equals(row.getCreatorPhoneSource())
                    ? GroupCreatorPhoneSource.JID_DERIVED.code() : GroupCreatorPhoneSource.CONFIRMED.code();
            if (phone == null) {
                String jid = row.getGroupJid() == null ? "" : row.getGroupJid().trim();
                Matcher match = LEGACY_GROUP_JID.matcher(jid);
                phone = match.matches() ? match.group(1) : null;
                source = GroupCreatorPhoneSource.JID_DERIVED.code();
            }
            row.setOwnerPhone(phone);
            row.setOwnerPhoneObserved(phone != null);
            row.setCreatorPhoneSource(phone == null ? GroupCreatorPhoneSource.UNKNOWN.code() : source);
        }
        List<String> phones = rows.stream().map(GroupLinkPreview::getOwnerPhone)
                .filter(java.util.Objects::nonNull).distinct().toList();
        Map<String, CountryReferenceVO> countries = resolveCountries(phones);
        for (GroupLinkPreview row : rows) {
            CountryReferenceVO country = row.getOwnerPhone() == null ? null : countries.get(row.getOwnerPhone());
            row.setCreatorCountryIso2(country == null ? null : country.iso2());
            row.setCreatorContinentCode(country == null ? null : country.continentCode());
            row.setCreatorCountryObserved(country != null);
        }
    }

    private Map<String, CountryReferenceVO> resolveCountries(List<String> phones) {
        if (phones.isEmpty()) {
            return Map.of();
        }
        try {
            return countryService.resolveActiveCountriesByPhoneNumbers(phones);
        } catch (RuntimeException e) {
            log.warn("建群人号码国家解析失败,仅写号码 count={} errorType={}", phones.size(),
                    e.getClass().getSimpleName());
            return Map.of();
        }
    }
}
