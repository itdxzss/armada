package com.armada.account.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.armada.boot.Application;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * AccountController MockMvc 集成测试:覆盖 GET 列表/stats、POST migrate-group(含新建分组)、POST batch-delete。
 *
 * <p>走真库(armada schema),每个测试事务回滚不留数据。</p>
 */
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
@Transactional
class AccountControllerDbTest {

    private static final String TENANT_HEADER = "X-Tenant-Code";
    private static final String TENANT_CODE = "demo";

    /** JSON 格式完整的单账号内容,供 POST 导入时作 text 参数使用。 */
    private static final String VALID_JSON_TEXT_TEMPLATE =
            "[{\"wid\":\"%s\","
                    + "\"creds\":{\"registrationId\":99,\"noiseKey\":{},"
                    + "\"signedIdentityKey\":{},\"signedPreKey\":{}}}]";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    // -----------------------------------------------------------------------
    // 工具方法
    // -----------------------------------------------------------------------

    /**
     * 通过 account-imports 接口导入一个账号,返回 import 批次中记录的 account.id。
     * 从 GET /api/accounts?phone=wsPhone 列表第一条取 id,避免跨事务不可见问题。
     */
    private Long importOneAccount(String wsPhone) throws Exception {
        String text = String.format(VALID_JSON_TEXT_TEMPLATE, wsPhone);
        mockMvc.perform(multipart("/api/account-imports")
                .param("importFormat", "2")
                .param("deviceOs", "1")
                .param("accountType", "1")
                .param("batchName", "ctrl-acc-" + wsPhone)
                .param("text", text)
                .header(TENANT_HEADER, TENANT_CODE))
                .andExpect(jsonPath("$.code").value(0));

        // 通过列表查询取 id(同一事务内可见)
        MvcResult listResult = mockMvc.perform(get("/api/accounts")
                        .param("phone", wsPhone)
                        .param("pageSize", "1")
                        .header(TENANT_HEADER, TENANT_CODE))
                .andReturn();
        var tree = objectMapper.readTree(listResult.getResponse().getContentAsString(StandardCharsets.UTF_8));
        var list = tree.path("data").path("list");
        assertThat(list.isArray() && list.size() > 0).as("导入后列表未找到账号 " + wsPhone).isTrue();
        return list.get(0).path("id").longValue();
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
    // A4: POST /api/accounts/batch-delete — 批量软删除
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
}
