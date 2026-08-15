package com.armada.group.service.impl;

import com.armada.group.mapper.WhatsappGroupDepartedMemberMapper;
import com.armada.group.model.dto.WhatsappGroupDepartureFact;
import com.armada.group.model.vo.WhatsappGroupDepartedMemberVO;
import com.armada.group.service.WhatsappGroupDepartedMemberService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** MySQL 实现的 WhatsApp 群成员最近退群事实服务。 */
@Service
public class WhatsappGroupDepartedMemberServiceImpl implements WhatsappGroupDepartedMemberService {

    private final WhatsappGroupDepartedMemberMapper mapper;
    private final AccountGroupCurrentSnapshotPersistenceImpl currentPersistence;

    public WhatsappGroupDepartedMemberServiceImpl(
            WhatsappGroupDepartedMemberMapper mapper,
            AccountGroupCurrentSnapshotPersistenceImpl currentPersistence) {
        this.mapper = mapper;
        this.currentPersistence = currentPersistence;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveLatest(List<WhatsappGroupDepartureFact> facts) {
        if (facts == null || facts.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        facts.stream()
                .sorted(Comparator.comparing(WhatsappGroupDepartureFact::tenantId)
                        .thenComparing(WhatsappGroupDepartureFact::groupJid)
                        .thenComparing(WhatsappGroupDepartureFact::participantJid))
                .forEach(fact -> {
                    mapper.upsertIdentity(fact, now);
                    mapper.updateIfNewer(fact, now);
                });
        currentPersistence.applyParticipantDepartures(facts);
    }

    @Override
    @Transactional(readOnly = true)
    public List<WhatsappGroupDepartedMemberVO> findByGroupJids(Long tenantId, List<String> groupJids) {
        if (tenantId == null || groupJids == null || groupJids.isEmpty()) {
            return List.of();
        }
        List<String> normalized = groupJids.stream()
                .filter(groupJid -> groupJid != null && !groupJid.isBlank())
                .map(String::trim)
                .distinct()
                .sorted()
                .toList();
        if (normalized.isEmpty()) {
            return List.of();
        }
        List<WhatsappGroupDepartedMemberVO> result = new ArrayList<>();
        for (int start = 0; start < normalized.size(); start += 500) {
            result.addAll(mapper.selectByGroupJids(
                    tenantId,
                    normalized.subList(start, Math.min(start + 500, normalized.size()))));
        }
        result.sort(Comparator.comparing(WhatsappGroupDepartedMemberVO::groupJid)
                .thenComparing(WhatsappGroupDepartedMemberVO::exitedAt)
                .thenComparing(WhatsappGroupDepartedMemberVO::participantJid));
        return List.copyOf(result);
    }
}
