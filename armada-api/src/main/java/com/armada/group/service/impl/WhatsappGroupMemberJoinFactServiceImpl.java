package com.armada.group.service.impl;

import com.armada.group.mapper.WhatsappGroupMemberJoinFactMapper;
import com.armada.group.model.dto.WhatsappGroupJoinFact;
import com.armada.group.model.vo.WhatsappGroupJoinFactVO;
import com.armada.group.service.WhatsappGroupMemberJoinFactService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** MySQL 实现的 WhatsApp 群成员最近进群事实服务。 */
@Service
public class WhatsappGroupMemberJoinFactServiceImpl implements WhatsappGroupMemberJoinFactService {

    private final WhatsappGroupMemberJoinFactMapper mapper;
    private final AccountGroupCurrentSnapshotPersistenceImpl currentPersistence;

    public WhatsappGroupMemberJoinFactServiceImpl(
            WhatsappGroupMemberJoinFactMapper mapper,
            AccountGroupCurrentSnapshotPersistenceImpl currentPersistence) {
        this.mapper = mapper;
        this.currentPersistence = currentPersistence;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveLatest(List<WhatsappGroupJoinFact> facts) {
        if (facts == null || facts.isEmpty()) {
            return;
        }
        currentPersistence.applyParticipantJoins(facts);
    }

    @Override
    @Transactional(readOnly = true)
    public List<WhatsappGroupJoinFactVO> findByGroupJids(Long tenantId, List<String> groupJids) {
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
        List<WhatsappGroupJoinFactVO> result = new ArrayList<>();
        for (int start = 0; start < normalized.size(); start += 500) {
            result.addAll(mapper.selectByGroupJids(
                    tenantId,
                    normalized.subList(start, Math.min(start + 500, normalized.size()))));
        }
        result.sort(Comparator.comparing(WhatsappGroupJoinFactVO::groupJid)
                .thenComparing(WhatsappGroupJoinFactVO::joinedAt)
                .thenComparing(WhatsappGroupJoinFactVO::participantJid));
        return List.copyOf(result);
    }
}
