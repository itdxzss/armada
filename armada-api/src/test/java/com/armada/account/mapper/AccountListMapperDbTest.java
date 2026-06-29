package com.armada.account.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.account.model.dto.AccountQuery;
import com.armada.account.model.entity.Account;
import com.armada.account.model.entity.AccountGroup;
import com.armada.account.model.entity.AccountState;
import com.armada.account.model.vo.AccountListVoRow;
import com.armada.testsupport.DbTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 账号列表查询 Mapper 真库测试:验 countPage/selectPage 的三表 LEFT JOIN、8 维筛选下推、
 * groupName 来自 JOIN、状态列 step1 全 NULL。
 * 每个 @Test 在 @Transactional 内执行并回滚,数据互不干扰。
 */
class AccountListMapperDbTest extends DbTestBase {

    @Autowired
    AccountMapper accountMapper;

    @Autowired
    AccountStateMapper stateMapper;

    @Autowired
    AccountGroupMapper groupMapper;

    @Autowired
    JdbcTemplate jdbc;

    // ---- 辅助方法 ----

    /**
     * 构造最小合法 Account 并插入,返回落库后的实体(id 已回填)。
     * 不设 accountGroupId,由调用方按需覆盖。
     */
    private Account insertAccount(String wsPhone, long now) {
        Account a = new Account();
        a.setWsPhone(wsPhone);
        a.setAccountType(1);   // 个人号
        a.setOwnership(1);     // 自有
        a.setPriority(0);
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        accountMapper.insert(a);
        return a;
    }

    /**
     * 为已插入的 Account 插入默认状态行(step1 全 NULL 状态列)。
     */
    private void insertDefaultState(Long accountId, long now) {
        AccountState s = new AccountState();
        s.setAccountId(accountId);
        s.setProxyFailureCount(0);
        s.setPullIntoGroupCount(0);
        s.setCreatedAt(now);
        s.setUpdatedAt(now);
        stateMapper.insert(s);
    }

    /**
     * 创建一个账号分组并插入,返回其 id。
     */
    private Long insertGroup(String name, long now) {
        AccountGroup g = new AccountGroup();
        g.setName(name);
        g.setSystemBuiltin(0);
        g.setCreatedAt(now);
        g.setUpdatedAt(now);
        groupMapper.insert(g);
        return g.getId();
    }

    // ---- 测试用例 ----

    /**
     * 插入 2 个账号 + 默认状态行,selectPage 应返回至少 2 行,countPage 至少 2;
     * 状态列全为 NULL(step1 导入初态);其中挂了分组的账号 groupName 来自 JOIN。
     */
    @Test
    void listAccounts_twoRows_stateNullAndGroupNameJoined() {
        long now = System.currentTimeMillis();

        // 创建分组并关联第 1 个账号
        Long groupId = insertGroup("列表测试分组-" + now, now);
        Account a1 = insertAccount("86139" + (now % 100000000L), now);
        // 回写 accountGroupId:先 insert 再 update account_group_id
        // 直接通过 a1 重新 insert 一条带 groupId 的账号更简洁:先删旧再建带组的
        // 实际更优:账号 insert 时就带 accountGroupId
        Account a2WithGroup = new Account();
        a2WithGroup.setWsPhone("86138" + (now % 100000000L));
        a2WithGroup.setAccountType(2);
        a2WithGroup.setOwnership(1);
        a2WithGroup.setAccountGroupId(groupId);
        a2WithGroup.setPriority(0);
        a2WithGroup.setCreatedAt(now - 1000);
        a2WithGroup.setUpdatedAt(now - 1000);
        accountMapper.insert(a2WithGroup);
        insertDefaultState(a2WithGroup.getId(), now);

        insertDefaultState(a1.getId(), now);

        AccountQuery q = new AccountQuery();
        // 使用默认分页(page=1, pageSize=10)

        long total = accountMapper.countPage(q);
        assertThat(total).isGreaterThanOrEqualTo(2);

        List<AccountListVoRow> page = accountMapper.selectPage(q);
        assertThat(page).hasSizeGreaterThanOrEqualTo(2);

        // 验有分组账号的 groupName
        AccountListVoRow withGroup = page.stream()
                .filter(r -> r.getId().equals(a2WithGroup.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到带分组账号"));
        assertThat(withGroup.getGroupName()).isEqualTo("列表测试分组-" + now);
        assertThat(withGroup.getAccountGroupId()).isEqualTo(groupId);

        // 验无分组账号 groupName 为 null
        AccountListVoRow noGroup = page.stream()
                .filter(r -> r.getId().equals(a1.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到无分组账号"));
        assertThat(noGroup.getGroupName()).isNull();

        // step1 状态列全 NULL
        page.stream()
            .filter(r -> r.getId().equals(a1.getId()) || r.getId().equals(a2WithGroup.getId()))
            .forEach(r -> {
                assertThat(r.getAccountState()).isNull();
                assertThat(r.getLoginState()).isNull();
                assertThat(r.getRiskStatus()).isNull();
            });
    }

    /**
     * phone 开头模糊筛选:只有前缀匹配的账号出现在结果中。
     */
    @Test
    void listAccounts_filterByPhonePrefix_onlyMatchingReturned() {
        long now = System.currentTimeMillis();
        // 使用时间戳保证唯一性
        String prefixA = "8613" + (now % 10000000L);
        String prefixB = "8614" + (now % 10000000L);

        Account aA = insertAccount(prefixA + "1", now);
        Account aB = insertAccount(prefixB + "2", now);
        insertDefaultState(aA.getId(), now);
        insertDefaultState(aB.getId(), now);

        AccountQuery q = new AccountQuery();
        q.setPhone(prefixA);

        long total = accountMapper.countPage(q);
        assertThat(total).isGreaterThanOrEqualTo(1);

        List<AccountListVoRow> page = accountMapper.selectPage(q);
        // 所有结果 wsPhone 均以 prefixA 开头
        page.forEach(r -> assertThat(r.getWsPhone()).startsWith(prefixA));
        // prefixB 的账号不出现
        boolean hasBPhone = page.stream()
                .anyMatch(r -> r.getId().equals(aB.getId()));
        assertThat(hasBPhone).isFalse();
    }

    /**
     * selectActiveById:按主键能查回未软删账号,供手动上线入口加载账号身份。
     */
    @Test
    void selectActiveById_returnsInsertedActiveAccount() {
        long now = System.currentTimeMillis();
        Account account = insertAccount("86136" + (now % 10000000L), now);

        Account found = accountMapper.selectActiveById(account.getId());

        assertThat(found).isNotNull();
        assertThat(found.getId()).isEqualTo(account.getId());
        assertThat(found.getWsPhone()).isEqualTo(account.getWsPhone());
    }

    /**
     * accountGroupId 等值筛选:只返回该分组账号;另一分组账号不出现。
     */
    @Test
    void listAccounts_filterByGroupId_onlyGroupAccountsReturned() {
        long now = System.currentTimeMillis();
        Long groupX = insertGroup("分组X-" + now, now);
        Long groupY = insertGroup("分组Y-" + now, now);

        Account ax = new Account();
        ax.setWsPhone("86131" + (now % 10000000L));
        ax.setAccountType(1);
        ax.setOwnership(1);
        ax.setAccountGroupId(groupX);
        ax.setPriority(0);
        ax.setCreatedAt(now);
        ax.setUpdatedAt(now);
        accountMapper.insert(ax);
        insertDefaultState(ax.getId(), now);

        Account ay = new Account();
        ay.setWsPhone("86132" + (now % 10000000L));
        ay.setAccountType(1);
        ay.setOwnership(1);
        ay.setAccountGroupId(groupY);
        ay.setPriority(0);
        ay.setCreatedAt(now);
        ay.setUpdatedAt(now);
        accountMapper.insert(ay);
        insertDefaultState(ay.getId(), now);

        AccountQuery q = new AccountQuery();
        q.setAccountGroupId(groupX);

        long total = accountMapper.countPage(q);
        assertThat(total).isGreaterThanOrEqualTo(1);

        List<AccountListVoRow> page = accountMapper.selectPage(q);
        // 所有结果均属 groupX
        page.forEach(r -> assertThat(r.getAccountGroupId()).isEqualTo(groupX));
        // groupY 账号不出现
        boolean hasGroupY = page.stream().anyMatch(r -> r.getId().equals(ay.getId()));
        assertThat(hasGroupY).isFalse();
    }

    /**
     * accountState 筛选:写入一个 state=2(正常)账号,按 accountState=2 筛选只命中该行。
     * 同时验证 numberSource=1(TINYINT)写入后 selectPage 读出为 Integer(1),不抛 TypeException。
     */
    @Test
    void listAccounts_filterByAccountState_onlyMatchingReturned() {
        long now = System.currentTimeMillis();

        // 目标账号:number_source=1(买量),state=2(正常)
        Account target = new Account();
        target.setWsPhone("86177" + (now % 10000000L));
        target.setAccountType(1);
        target.setOwnership(1);
        target.setNumberSource(1);  // Integer,验 TINYINT→Integer 无 TypeException
        target.setPriority(0);
        target.setCreatedAt(now);
        target.setUpdatedAt(now);
        accountMapper.insert(target);

        // 插入默认状态行,再 UPDATE account_state 列(insert XML 只写固定列,用 JdbcTemplate 补)
        insertDefaultState(target.getId(), now);
        jdbc.update("UPDATE account_state SET account_state = 2 WHERE account_id = ?", target.getId());

        // 另一个账号:state=1(新增)
        Account other = insertAccount("86178" + (now % 10000000L), now);
        insertDefaultState(other.getId(), now);
        jdbc.update("UPDATE account_state SET account_state = 1 WHERE account_id = ?", other.getId());

        AccountQuery q = new AccountQuery();
        q.setAccountState(2);

        long count = accountMapper.countPage(q);
        assertThat(count).isGreaterThanOrEqualTo(1);

        List<AccountListVoRow> page = accountMapper.selectPage(q);
        // 目标账号存在于结果
        AccountListVoRow row = page.stream()
                .filter(r -> r.getId().equals(target.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到 state=2 的目标账号"));
        assertThat(row.getAccountState()).isEqualTo(2);
        // 验 numberSource=1 读出为 Integer(不炸 TypeException)
        assertThat(row.getNumberSource()).isEqualTo(1);

        // state=1 的账号不出现
        boolean hasOther = page.stream().anyMatch(r -> r.getId().equals(other.getId()));
        assertThat(hasOther).isFalse();
    }

    /**
     * 一期账号列表可用筛选必须真实 SQL 下推:关键字/账号/账号类型/协议/来源/登录/风控/
     * 禁言/国家/IP 地址同时生效,不能只在前端展示。
     */
    @Test
    void listAccounts_filterByAvailableListFields_onlyMatchingReturned() {
        long now = System.currentTimeMillis();
        String phonePrefix = "86160" + (now % 1000000L);
        String protocolId = "proto-" + now;
        String channelName = "渠道-" + now;
        String remark = "备注-" + now;

        Account target = new Account();
        target.setWsPhone(phonePrefix + "1");
        target.setAccountType(2);
        target.setOwnership(1);
        target.setNumberSource(3);
        target.setChannelName(channelName);
        target.setProtocolId(protocolId);
        target.setRemark(remark);
        target.setPriority(0);
        target.setCreatedAt(now);
        target.setUpdatedAt(now);
        accountMapper.insert(target);
        insertDefaultState(target.getId(), now);
        jdbc.update("""
                UPDATE account_state
                SET login_state = 1,
                    risk_status = 2,
                    mute_status = 1,
                    truth_ip = '203.0.113.10',
                    proxy_country = '印度'
                WHERE account_id = ?
                """, target.getId());

        Account distractor = new Account();
        distractor.setWsPhone(phonePrefix + "9");
        distractor.setAccountType(2);
        distractor.setOwnership(1);
        distractor.setNumberSource(3);
        distractor.setChannelName("其他渠道-" + now);
        distractor.setProtocolId(protocolId);
        distractor.setRemark("其他备注-" + now);
        distractor.setPriority(0);
        distractor.setCreatedAt(now - 1);
        distractor.setUpdatedAt(now - 1);
        accountMapper.insert(distractor);
        insertDefaultState(distractor.getId(), now);
        jdbc.update("""
                UPDATE account_state
                SET login_state = 2,
                    risk_status = 2,
                    mute_status = 2,
                    truth_ip = '198.51.100.20',
                    proxy_country = '巴西'
                WHERE account_id = ?
                """, distractor.getId());

        AccountQuery q = new AccountQuery();
        q.setKeyword(remark);
        q.setPhone(phonePrefix);
        q.setAccountType(2);
        q.setProtocolId(protocolId);
        q.setNumberSource(3);
        q.setChannelName(channelName);
        q.setLoginState(1);
        q.setRiskStatus(2);
        q.setMuteStatus(1);
        q.setCountry("印度");
        q.setTruthIp("203.0.113");
        q.setPageSize(50);

        long count = accountMapper.countPage(q);
        List<AccountListVoRow> rows = accountMapper.selectPage(q);

        assertThat(count).isEqualTo(1);
        assertThat(rows).extracting(AccountListVoRow::getId).containsExactly(target.getId());
    }

    /**
     * 状态回写尚未带回出口 IP 时,账号列表应从当前绑定代理兜底展示国家/IP 来源/IP 地址。
     */
    @Test
    void listAccounts_fallsBackToBoundProxyWhenStateIpFieldsAreNull() {
        long now = System.currentTimeMillis();
        String wsPhone = "86165" + (now % 100000000L);

        Account account = insertAccount(wsPhone, now);
        insertDefaultState(account.getId(), now);
        jdbc.update("""
                INSERT INTO ip_proxy (
                    tenant_id, host, port, protocol, username, password,
                    region, status, bound_account_id, bound_at, source, ownership,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                TEST_TENANT_ID, "geo.iproyal.com", 12321, 2, "proxy-user", "proxy-pass",
                "印度", 2, account.getId(), now, "iproyal", 1, now, now);

        AccountQuery q = new AccountQuery();
        q.setPhone(wsPhone);
        q.setCountry("印度");
        q.setTruthIp("geo.iproyal");
        q.setPageSize(10);

        long count = accountMapper.countPage(q);
        List<AccountListVoRow> rows = accountMapper.selectPage(q);

        assertThat(count).isEqualTo(1);
        assertThat(rows).hasSize(1);
        AccountListVoRow row = rows.get(0);
        assertThat(row.getId()).isEqualTo(account.getId());
        assertThat(row.getProxyCountry()).isEqualTo("印度");
        assertThat(row.getIpSource()).isEqualTo("iproyal");
        assertThat(row.getTruthIp()).isEqualTo("geo.iproyal.com");
    }

    /**
     * countPage 与 selectPage 数量一致(同 query,同 filter)。
     */
    @Test
    void countPage_matchesSelectPage_size() {
        long now = System.currentTimeMillis();
        Long group = insertGroup("分组-count-" + now, now);

        for (int i = 0; i < 3; i++) {
            Account a = new Account();
            a.setWsPhone("86199" + now + i);
            a.setAccountType(1);
            a.setOwnership(1);
            a.setAccountGroupId(group);
            a.setPriority(0);
            a.setCreatedAt(now);
            a.setUpdatedAt(now);
            accountMapper.insert(a);
            insertDefaultState(a.getId(), now);
        }

        AccountQuery q = new AccountQuery();
        q.setAccountGroupId(group);
        q.setPageSize(500);

        long count = accountMapper.countPage(q);
        List<AccountListVoRow> rows = accountMapper.selectPage(q);
        assertThat(rows).hasSize((int) count);
    }
}
