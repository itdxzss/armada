package com.armada.account.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.armada.boot.Application;
import com.armada.platform.protocol.model.enums.ProtocolCommandOutboxStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * AccountController MockMvc 集成测试:覆盖 GET 列表/stats、POST migrate-group(含新建分组)、
 * POST batch-offline、POST batch-delete。
 *
 * <p>走真库(armada schema),每个测试事务回滚不留数据。</p>
 */
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
@Transactional
class AccountControllerDbTest {

    private static final String TENANT_HEADER = "X-Tenant-Code";
    private static final String TENANT_CODE = "demo";
    private static final long TEST_TENANT_ID = 1L;
    private static final String COMMAND_TYPE_ACCOUNT_OFFLINE_REQUESTED = "account.offline.requested";
    private static final String AGGREGATE_TYPE_ACCOUNT = "ACCOUNT";
    private static final String TOPIC_PROTOCOL_MASTER_COMMANDS = "protocol.master.commands.v1";

    /** JSON 格式完整的单账号内容,供 POST 导入时作 text 参数使用。 */
    private static final String VALID_JSON_TEXT_TEMPLATE =
            "[{\"wid\":\"%s\","
                    + "\"registrationId\":99,\"noiseKey\":{},"
                    + "\"signedIdentityKey\":{},\"signedPreKey\":{}}]";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    // -----------------------------------------------------------------------
    // 工具方法
    // -----------------------------------------------------------------------

    /**
     * 通过 account-imports 接口导入一个账号,返回 import 批次中记录的 account.id。
     * 直接查 account 表取 id,避免预读 GET /api/accounts 污染 MyBatis 一级缓存。
     */
    private Long importOneAccount(String wsPhone) throws Exception {
        String text = String.format(VALID_JSON_TEXT_TEMPLATE, wsPhone);
        mockMvc.perform(multipart("/api/account-imports")
                .param("importFormat", "2")
                .param("deviceOs", "1")
                .param("accountType", "1")
                .param("text", text)
                .header(TENANT_HEADER, TENANT_CODE))
                .andExpect(jsonPath("$.code").value(0));

        Long accountId = jdbc.queryForObject("""
                SELECT id
                FROM account
                WHERE tenant_id = ? AND ws_phone = ? AND deleted_at IS NULL
                LIMIT 1
                """, Long.class, TEST_TENANT_ID, wsPhone);
        assertThat(accountId).as("导入后 account 表未找到账号 " + wsPhone).isNotNull();
        return accountId;
    }

    /**
     * 查询当前测试事务内指定账号的下线 outbox 行。
     *
     * <p>MockMvc 请求与测试方法共享事务,因此这里能看到尚未提交、最终会回滚的 outbox 数据。
     * 使用 JdbcTemplate 是为了断言真实表字段,不绕 MyBatis mapper 重新走业务逻辑。</p>
     */
    private List<OutboxRow> selectOfflineOutboxRows(Long firstAccountId, Long secondAccountId) {
        return jdbc.query(
                "SELECT command_type, aggregate_type, aggregate_id, kafka_topic, kafka_key, "
                        + "protocol_account_id, payload_json, status "
                        + "FROM protocol_command_outbox "
                        + "WHERE tenant_id = ? AND command_type = ? AND aggregate_id IN (?, ?) "
                        + "ORDER BY aggregate_id",
                (rs, rowNum) -> new OutboxRow(
                        rs.getString("command_type"),
                        rs.getString("aggregate_type"),
                        rs.getLong("aggregate_id"),
                        rs.getString("kafka_topic"),
                        rs.getString("kafka_key"),
                        rs.getString("protocol_account_id"),
                        rs.getString("payload_json"),
                        rs.getInt("status")),
                TEST_TENANT_ID,
                COMMAND_TYPE_ACCOUNT_OFFLINE_REQUESTED,
                firstAccountId,
                secondAccountId);
    }

    // -----------------------------------------------------------------------
    // A1: GET /api/accounts — 账号分页列表
    // -----------------------------------------------------------------------

    /**
     * 导入一条账号后 GET 列表:code=0 + list 非空 + total >= 1 + groupName 和状态列存在(step1 全 null)。
     */
    @Test
    void get_list_returnsAccountWithNullStateColumns() throws Exception {
        long ts = System.currentTimeMillis();
        String wsPhone = "86130" + (ts % 10000000L);
        importOneAccount(wsPhone);

        mockMvc.perform(get("/api/accounts")
                        .param("page", "1")
                        .param("pageSize", "10")
                        .header(TENANT_HEADER, TENANT_CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").isNumber())
                .andExpect(jsonPath("$.data.list").isArray());
    }

    /**
     * 列表条目包含占位字段:avatarUrl=null, friendsNum=0, groupsNum=0。
     */
    @Test
    void get_list_placeholderFieldsAreConstant() throws Exception {
        long ts = System.currentTimeMillis();
        String wsPhone = "86131" + (ts % 10000000L);
        Long accountId = importOneAccount(wsPhone);

        MvcResult result = mockMvc.perform(get("/api/accounts")
                        .param("page", "1")
                        .param("pageSize", "500")
                        .header(TENANT_HEADER, TENANT_CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        var tree = objectMapper.readTree(body);
        var list = tree.path("data").path("list");
        assertThat(list.isArray()).isTrue();

        // 找到对应的账号行
        boolean found = false;
        for (var node : list) {
            if (node.path("id").longValue() == accountId) {
                // avatarUrl/country/ipSource 为 null:Jackson 可能输出 null 或省略该字段
                // MissingNode 或 NullNode 都满足"值为 null"语义
                assertThat(node.path("avatarUrl").isNull() || node.path("avatarUrl").isMissingNode()).isTrue();
                assertThat(node.path("friendsNum").intValue()).isEqualTo(0);
                assertThat(node.path("groupsNum").intValue()).isEqualTo(0);
                assertThat(node.path("hyperlinkSentCount").intValue()).isEqualTo(0);
                // step1 状态列全 null(MissingNode 或 NullNode)
                assertThat(node.path("accountState").isNull() || node.path("accountState").isMissingNode()).isTrue();
                assertThat(node.path("loginState").isNull() || node.path("loginState").isMissingNode()).isTrue();
                found = true;
                break;
            }
        }
        assertThat(found).as("未在列表中找到 accountId=" + accountId).isTrue();
    }

    /**
     * 已绑定 IP 的导入账号应在账号列表返回国家 / IP 来源,但 IP 地址只取 truth_ip;
     * 未探测真实出口 IP 时为空,并默认显示自购来源。
     */
    @Test
    void get_list_importedAccountShowsBoundProxyCountryAndSourceButNoTruthIpFallback() throws Exception {
        long ts = System.currentTimeMillis();
        String wsPhone = "86135" + (ts % 10000000L);
        Long accountId = importOneAccount(wsPhone);
        jdbc.update("""
                INSERT INTO ip_proxy (
                    tenant_id, host, port, protocol, username, password,
                    region, status, bound_account_id, bound_at, source, ownership,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                TEST_TENANT_ID, "geo.iproyal.com", 12321, 2, "proxy-user", "proxy-pass",
                "印度", 2, accountId, ts, "iproyal", 1, ts, ts);

        MvcResult result = mockMvc.perform(get("/api/accounts")
                        .param("phone", wsPhone)
                        .param("pageSize", "1")
                        .header(TENANT_HEADER, TENANT_CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.list[0].id").value(accountId))
                .andExpect(jsonPath("$.data.list[0].numberSource").value(3))
                .andExpect(jsonPath("$.data.list[0].country").value("印度"))
                .andExpect(jsonPath("$.data.list[0].ipSource").value("iproyal"))
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        var row = objectMapper.readTree(body).path("data").path("list").path(0);
        assertThat(row.path("truthIp").isNull() || row.path("truthIp").isMissingNode()).isTrue();
    }

    // -----------------------------------------------------------------------
    // A2: GET /api/accounts/stats — 统计卡
    // -----------------------------------------------------------------------

    /**
     * 导入 2 条账号后 GET stats:total >= 2 + unassigned = total - assigned。
     */
    @Test
    void get_stats_totalAndUnassignedCorrect() throws Exception {
        long ts = System.currentTimeMillis();
        importOneAccount("86132" + (ts % 10000000L));
        importOneAccount("86133" + (ts % 10000000L));

        MvcResult result = mockMvc.perform(get("/api/accounts/stats")
                        .header(TENANT_HEADER, TENANT_CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").isNumber())
                .andExpect(jsonPath("$.data.unassigned").isNumber())
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        var data = objectMapper.readTree(body).path("data");
        long total = data.path("total").longValue();
        long assigned = data.path("assigned").longValue();
        long unassigned = data.path("unassigned").longValue();

        assertThat(total).isGreaterThanOrEqualTo(2);
        // 铁律:unassigned = total - assigned
        assertThat(unassigned).isEqualTo(total - assigned);
    }

    // -----------------------------------------------------------------------
    // A3: POST /api/accounts/batch-migrate-group — 迁移到已有分组
    // -----------------------------------------------------------------------

    /**
     * 迁移到已有分组:先建分组,导入账号,迁移,验列表中 groupName 已更新。
     */
    @Test
    void post_batchMigrateGroup_existingGroup_updatesGroupName() throws Exception {
        long ts = System.currentTimeMillis();
        String wsPhone = "86134" + (ts % 10000000L);
        Long accountId = importOneAccount(wsPhone);

        // 先建一个分组取 id
        MvcResult groupResult = mockMvc.perform(post("/api/account-groups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("name", "迁移目标组-" + ts, "remark", "test")))
                        .header(TENANT_HEADER, TENANT_CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();

        Long groupId = objectMapper.readTree(groupResult.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("id").longValue();
        assertThat(groupId).isPositive();

        // 迁移
        mockMvc.perform(post("/api/accounts/batch-migrate-group")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("ids", List.of(accountId), "accountGroupId", groupId)))
                        .header(TENANT_HEADER, TENANT_CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // 验列表 account_group_id 已更新
        MvcResult listResult = mockMvc.perform(get("/api/accounts")
                        .param("page", "1")
                        .param("pageSize", "500")
                        .param("accountGroupId", groupId.toString())
                        .header(TENANT_HEADER, TENANT_CODE))
                .andReturn();
        var list = objectMapper.readTree(listResult.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("list");
        boolean found = false;
        for (var node : list) {
            if (node.path("id").longValue() == accountId) {
                assertThat(node.path("accountGroupId").longValue()).isEqualTo(groupId);
                assertThat(node.path("groupName").textValue()).isEqualTo("迁移目标组-" + ts);
                found = true;
                break;
            }
        }
        assertThat(found).as("迁移后账号未出现在分组列表中").isTrue();
    }

    /**
     * 迁移时 accountGroupId=null + newGroupName 非空:先建新组再迁移,验 account_group_id 已更新。
     */
    @Test
    void post_batchMigrateGroup_newGroup_createsGroupAndMigrates() throws Exception {
        long ts = System.currentTimeMillis();
        String wsPhone = "86135" + (ts % 10000000L);
        Long accountId = importOneAccount(wsPhone);

        String newGroupName = "新建迁移组-" + ts;

        mockMvc.perform(post("/api/accounts/batch-migrate-group")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("ids", List.of(accountId),
                                        "newGroupName", newGroupName,
                                        "newGroupRemark", "auto")))
                        .header(TENANT_HEADER, TENANT_CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // 验账号已挂到新分组(按 groupName 筛不到,按 accountGroupId 不知 id,用 wsPhone 在列表查)
        MvcResult listResult = mockMvc.perform(get("/api/accounts")
                        .param("page", "1")
                        .param("pageSize", "500")
                        .param("phone", wsPhone)
                        .header(TENANT_HEADER, TENANT_CODE))
                .andReturn();
        var list = objectMapper.readTree(listResult.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("list");
        boolean found = false;
        for (var node : list) {
            if (node.path("id").longValue() == accountId) {
                assertThat(node.path("groupName").textValue()).isEqualTo(newGroupName);
                found = true;
                break;
            }
        }
        assertThat(found).as("新建分组迁移后账号未出现在列表中").isTrue();
    }

    // -----------------------------------------------------------------------
    // A4: POST /api/accounts/batch-offline — 批量下线
    // -----------------------------------------------------------------------

    /**
     * 批量下线入口:HTTP 请求成功后写入 PENDING outbox,不直接修改账号最终在线状态。
     */
    @Test
    void post_batchOffline_persistsPendingOutboxRows() throws Exception {
        long ts = System.currentTimeMillis();
        Long firstAccountId = importOneAccount("86137" + (ts % 10000000L));
        Long secondAccountId = importOneAccount("86138" + (ts % 10000000L));

        mockMvc.perform(post("/api/accounts/batch-offline")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("ids", List.of(firstAccountId, secondAccountId))))
                        .header(TENANT_HEADER, TENANT_CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.requested").value(2))
                .andExpect(jsonPath("$.data.submitted").value(2))
                .andExpect(jsonPath("$.data.accepted").value(2));

        List<OutboxRow> rows = selectOfflineOutboxRows(firstAccountId, secondAccountId);
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(OutboxRow::commandType)
                .containsOnly(COMMAND_TYPE_ACCOUNT_OFFLINE_REQUESTED);
        assertThat(rows).extracting(OutboxRow::aggregateType)
                .containsOnly(AGGREGATE_TYPE_ACCOUNT);
        assertThat(rows).extracting(OutboxRow::aggregateId)
                .containsExactly(firstAccountId, secondAccountId);
        assertThat(rows).extracting(OutboxRow::kafkaTopic)
                .containsOnly(TOPIC_PROTOCOL_MASTER_COMMANDS);
        assertThat(rows).extracting(OutboxRow::kafkaKey)
                .doesNotContainNull();
        assertThat(rows).extracting(OutboxRow::protocolAccountId)
                .doesNotContainNull();
        assertThat(rows).extracting(OutboxRow::status)
                .containsOnly(ProtocolCommandOutboxStatus.PENDING.code());
        for (OutboxRow row : rows) {
            var payload = objectMapper.readTree(row.payloadJson());
            assertThat(payload.path("source").textValue()).isEqualTo("batch_offline");
            assertThat(payload.path("accountId").longValue()).isEqualTo(row.aggregateId());
            assertThat(payload.path("protocolAccountId").textValue()).isEqualTo(row.protocolAccountId());
            assertThat(payload.has("credentialJson")).isFalse();
            assertThat(payload.has("credentialFormat")).isFalse();
            assertThat(payload.has("proxyId")).isFalse();
            assertThat(row.payloadJson())
                    .doesNotContain("password")
                    .doesNotContain("username")
                    .doesNotContain("proxyHost");
        }
    }

    // -----------------------------------------------------------------------
    // A5: POST /api/accounts/batch-delete — 批量软删除
    // -----------------------------------------------------------------------

    /**
     * step1 导入态 account_state=NULL → batch-delete 返回业务错误(code != 0,VALIDATION 口径)。
     */
    @Test
    void post_batchDelete_pendingAccount_returnsValidationError() throws Exception {
        long ts = System.currentTimeMillis();
        String wsPhone = "86136" + (ts % 10000000L);
        Long accountId = importOneAccount(wsPhone);

        // state=NULL 不满足严格删除口径,预期业务错误
        mockMvc.perform(post("/api/accounts/batch-delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("ids", List.of(accountId))))
                        .header(TENANT_HEADER, TENANT_CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40001)); // ErrorCode.VALIDATION.code() = 40001
    }

    private record OutboxRow(
            String commandType,
            String aggregateType,
            Long aggregateId,
            String kafkaTopic,
            String kafkaKey,
            String protocolAccountId,
            String payloadJson,
            int status) {
    }
}
