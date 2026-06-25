package com.armada.account.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.account.model.entity.Account;
import com.armada.account.model.entity.AccountCredential;
import com.armada.account.model.entity.AccountGroup;
import com.armada.account.model.entity.AccountImportBatch;
import com.armada.account.model.entity.AccountImportDetail;
import com.armada.account.model.entity.AccountState;
import com.armada.testsupport.DbTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 5 写路径 Mapper 真库测试:验「三步写最小链」链路 + DB 唯一键兜底。
 * 每个 @Test 在 @Transactional 内运行并回滚,数据互不干扰。
 */
class AccountImportWriteMapperDbTest extends DbTestBase {

    @Autowired
    AccountMapper accountMapper;

    @Autowired
    AccountStateMapper stateMapper;

    @Autowired
    AccountCredentialMapper credentialMapper;

    @Autowired
    AccountImportBatchMapper batchMapper;

    @Autowired
    AccountImportDetailMapper detailMapper;

    @Autowired
    AccountGroupMapper groupMapper;

    /** 构造最小合法 Account 实体(仅必填字段)。 */
    private Account newAccount(String wsPhone, long now) {
        Account a = new Account();
        a.setWsPhone(wsPhone);
        a.setAccountType(2);          // 商业号;导入即冻结
        a.setOwnership(1);            // 自有
        a.setPriority(0);
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        return a;
    }

    @Test
    void insertAccount_thenState_thenCredential_linked() {
        long now = 1_700_000_000_000L;
        Account a = new Account();
        a.setWsPhone("8613800138000");
        a.setAccountType(2);
        a.setAccountGroupId(null);
        a.setProtocolAccountId("acc_8613800138000");
        a.setOwnership(1);
        a.setPriority(0);
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        accountMapper.insert(a);
        assertThat(a.getId()).isNotNull();

        AccountState s = new AccountState();
        s.setAccountId(a.getId());
        s.setProxyFailureCount(0);
        s.setPullIntoGroupCount(0);
        s.setCreatedAt(now);
        s.setUpdatedAt(now);
        stateMapper.insert(s);                       // 状态列全 NULL=待上线

        AccountCredential c = new AccountCredential();
        c.setAccountId(a.getId());
        c.setWsPhone("8613800138000");
        c.setCredFormat(2);
        c.setCredsJson("{\"registrationId\":1}");
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        credentialMapper.insert(c);

        assertThat(accountMapper.selectActiveByWsPhone("8613800138000")).isNotNull();
        assertThat(stateMapper.selectByAccountId(a.getId())).isNotNull();
        assertThat(credentialMapper.selectByAccountId(a.getId())).isNotNull();
    }

    @Test
    void insertAccount_duplicateWsPhone_throwsDuplicateKey() {
        long now = 1_700_000_000_000L;
        Account a1 = newAccount("8613800138001", now);
        accountMapper.insert(a1);
        Account a2 = newAccount("8613800138001", now);
        assertThatThrownBy(() -> accountMapper.insert(a2))
            .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);   // uq_tenant_phone 兜底
    }

    /** 构造一个测试用 AccountGroup 并插入,返回其 id。 */
    private Long createTestGroup(long now) {
        AccountGroup g = new AccountGroup();
        g.setName("测试分组-" + System.nanoTime());
        g.setSystemBuiltin(0);
        g.setCreatedAt(now);
        g.setUpdatedAt(now);
        groupMapper.insert(g);
        return g.getId();
    }

    @Test
    void insertBatch_thenDetails_linked() {
        long now = 1_700_000_000_000L;

        // 先建分组(account_import_batch.account_group_id NOT NULL)
        Long groupId = createTestGroup(now);

        // 先建账号作为 detail 回填 accountId 用
        Account a = newAccount("8613800138002", now);
        accountMapper.insert(a);

        // 建批次
        AccountImportBatch batch = new AccountImportBatch();
        batch.setAccountGroupId(groupId);
        batch.setSourceFileName("测试批次");
        batch.setImportFormat(2);
        batch.setTotalRows(1);
        batch.setImportedRows(1);
        batch.setDuplicateRows(0);
        batch.setFormatErrorRows(0);
        batch.setStatus(2);           // 已完成
        batch.setCreatedAt(now);
        batchMapper.insert(batch);
        assertThat(batch.getId()).isNotNull();
        assertThat(batchMapper.selectById(batch.getId())).isNotNull();

        // 批量插入明细
        AccountImportDetail d1 = new AccountImportDetail();
        d1.setBatchId(batch.getId());
        d1.setLineNo(1);
        d1.setWsPhone("8613800138002");
        d1.setAccountId(a.getId());
        d1.setParseResult(1);         // 成功入库
        d1.setCreatedAt(now);

        AccountImportDetail d2 = new AccountImportDetail();
        d2.setBatchId(batch.getId());
        d2.setLineNo(2);
        d2.setWsPhone("8613800138099");
        d2.setParseResult(3);         // 格式错误
        d2.setFailReason("格式不合法");
        d2.setCreatedAt(now);

        int inserted = detailMapper.batchInsert(List.of(d1, d2));
        assertThat(inserted).isEqualTo(2);
    }
}
