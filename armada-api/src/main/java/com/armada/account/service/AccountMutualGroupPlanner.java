package com.armada.account.service;

import com.armada.account.mapper.AccountMutualContactMapper;
import com.armada.account.model.dto.AccountMutualContactCandidate;
import com.armada.account.model.vo.AccountMutualContactGroupVO;
import com.armada.account.model.vo.AccountMutualContactPreviewVO;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.security.AuthPrincipal;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;

/** 两分组互存与互聊共用的账号资格、归属、去重和冻结预览。 */
@Component
public class AccountMutualGroupPlanner {
    private final AccountMutualContactMapper mapper;

    /** 共用账号域现有的分组与候选查询，不复制 SQL。 */
    public AccountMutualGroupPlanner(AccountMutualContactMapper mapper) {
        this.mapper = mapper;
    }

    /** 分组与任务参数边界由各业务校验，本方法固定当前参与身份。 */
    public Plan plan(Request req, AuthPrincipal p) {
        AccountMutualContactAccess.require(p);
        var leftGroup = mapper.group(req.leftGroupId());
        var rightGroup = mapper.group(req.rightGroupId());
        if (leftGroup == null || rightGroup == null)
            throw invalid("账号分组不存在或无权访问");
        var identities = new HashSet<String>();
        var left = select(req.leftGroupId(), p, identities, req.maxOperations());
        var right = select(req.rightGroupId(), p, identities, req.maxOperations());
        long leftTotal = mapper.groupCount(req.leftGroupId()), rightTotal = mapper.groupCount(req.rightGroupId());
        long operationCount = 2L * left.size() * right.size();
        if (operationCount > req.maxOperations())
            throw invalid("本次双向操作超过 " + req.maxOperations() + " 次，请缩小分组");
        String material = p.tenantId() + ":" + p.userId() + ":" + req.leftGroupId() + ":" + req.rightGroupId() + ":"
                + req.intervalSeconds() + ":" + left + ":" + right;
        String token = DigestUtils.md5DigestAsHex(material.getBytes(StandardCharsets.UTF_8));
        return new Plan(left, right,
                new AccountMutualContactPreviewVO(
                        new AccountMutualContactGroupVO(leftGroup.getId(), leftGroup.getName(), leftTotal, left.size(),
                                leftTotal - left.size()),
                        new AccountMutualContactGroupVO(rightGroup.getId(), rightGroup.getName(), rightTotal,
                                right.size(), rightTotal - right.size()),
                        (int) operationCount / 2, (int) operationCount, token));
    }
    private List<AccountMutualContactCandidate> select(Long groupId, AuthPrincipal p, HashSet<String> seen, int maxOperations) {
        var rows = mapper.candidates(groupId, maxOperations / 2 + 1);
        if (rows.size() > maxOperations / 2)
            throw invalid("单组在线候选过多，请缩小分组");
        return rows.stream()
                .filter(AccountMutualContactAccess::eligible)
                .filter(a -> AccountMutualContactAccess.owns(p, a))
                .filter(a -> seen.add(a.wsPhone()))
                .toList();
    }
    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.VALIDATION, message);
    }
    /** 业务各自传入规模上限，互存和互聊的限额互不影响。 */
    public record Request(Long leftGroupId, Long rightGroupId, int intervalSeconds, int maxOperations) {}
    /** 冻结的左右身份和对应预览，重叠号码优先归左组。 */
    public record Plan(List<AccountMutualContactCandidate> left, List<AccountMutualContactCandidate> right,
            AccountMutualContactPreviewVO preview) {}
}
