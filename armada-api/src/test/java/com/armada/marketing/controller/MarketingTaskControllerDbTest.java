package com.armada.marketing.controller;

import com.armada.boot.Application;
import com.armada.marketing.model.dto.CreateMarketingTaskDTO;
import com.armada.marketing.model.dto.MarketingSelectionDTO;
import com.armada.marketing.model.dto.MarketingTemplateDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 营销任务 Controller 集成测试:覆盖创建、列表、详情三个第一阶段接口。
 *
 * <p>走完整 Spring MVC + 租户拦截器 + 真库 MyBatis。发送引擎不在本 checkpoint 覆盖。</p>
 */
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
@Transactional
class MarketingTaskControllerDbTest {

    private static final String TENANT_HEADER = "X-Tenant-Code";
    private static final String TENANT_CODE = "demo";
    private static final long TEST_TENANT_ID = 1L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void postCreate_returnsCreatedMarketingTask() throws Exception {
        Fixture fixture = seedFixture("controller-create");

        mockMvc.perform(post("/api/marketing-tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request("Controller创建任务", fixture)))
                        .header(TENANT_HEADER, TENANT_CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.taskName").value("Controller创建任务"))
                .andExpect(jsonPath("$.data.targetPairCount").value(1));
    }

    @Test
    void getList_returnsPageContainingCreatedMarketingTask() throws Exception {
        Fixture fixture = seedFixture("controller-list");
        long id = createTask("Controller列表任务", fixture);

        MvcResult result = mockMvc.perform(get("/api/marketing-tasks")
                        .param("keyword", "Controller列表")
                        .param("page", "1")
                        .param("pageSize", "10")
                        .header(TENANT_HEADER, TENANT_CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.list").isArray())
                .andReturn();

        var list = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("list");
        boolean found = false;
        for (var row : list) {
            if (row.path("id").asLong() == id) {
                found = true;
                assertThat(row.path("taskName").asText()).isEqualTo("Controller列表任务");
                break;
            }
        }
        assertThat(found).as("列表应包含刚创建的营销任务 id=" + id).isTrue();
    }

    @Test
    void getDetail_returnsTargets() throws Exception {
        Fixture fixture = seedFixture("controller-detail");
        long id = createTask("Controller详情任务", fixture);

        mockMvc.perform(get("/api/marketing-tasks/{id}", id)
                        .header(TENANT_HEADER, TENANT_CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(id))
                .andExpect(jsonPath("$.data.targets").isArray())
                .andExpect(jsonPath("$.data.targets[0].accountPhone").value(fixture.phone()))
                .andExpect(jsonPath("$.data.targets[0].groupJid").value(fixture.groupJid()));
    }

    @Test
    void postStartAndStop_updatesTaskStatus() throws Exception {
        Fixture fixture = seedFixture("controller-start-stop");
        long id = createTask("Controller启动停止任务", fixture);

        mockMvc.perform(post("/api/marketing-tasks/{id}/start", id)
                        .header(TENANT_HEADER, TENANT_CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(id))
                .andExpect(jsonPath("$.data.status").value(2))
                .andExpect(jsonPath("$.data.startedAt").isNumber());

        mockMvc.perform(post("/api/marketing-tasks/{id}/stop", id)
                        .header(TENANT_HEADER, TENANT_CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(id))
                .andExpect(jsonPath("$.data.status").value(5));
    }

    @Test
    void postBatchDelete_softDeletesTask() throws Exception {
        Fixture fixture = seedFixture("controller-batch-delete");
        long id = createTask("Controller批量删除任务", fixture);

        mockMvc.perform(post("/api/marketing-tasks/batch-delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("ids", List.of(id))))
                        .header(TENANT_HEADER, TENANT_CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(1));

        MvcResult result = mockMvc.perform(get("/api/marketing-tasks/{id}", id)
                        .header(TENANT_HEADER, TENANT_CODE))
                .andExpect(status().isOk())
                .andReturn();
        int code = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("code").intValue();
        assertThat(code).isNotEqualTo(0);
    }

    @Test
    void getAccountTree_returnsOnlineAccountAndGroups() throws Exception {
        Fixture fixture = seedFixture("controller-account-tree");

        mockMvc.perform(get("/api/marketing-tasks/account-tree")
                        .param("groupId", String.valueOf(fixture.accountGroupId()))
                        .header(TENANT_HEADER, TENANT_CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.accounts").isArray())
                .andExpect(jsonPath("$.data.accounts[0].accountId").value(fixture.accountId()))
                .andExpect(jsonPath("$.data.accounts[0].wsPhone").value(fixture.phone()))
                .andExpect(jsonPath("$.data.accounts[0].status").value("ONLINE"))
                .andExpect(jsonPath("$.data.accounts[0].groupsError").value(false))
                .andExpect(jsonPath("$.data.accounts[0].groups[0].groupLinkId").value(fixture.groupLinkId()))
                .andExpect(jsonPath("$.data.accounts[0].groups[0].groupJid").value(fixture.groupJid()))
                .andExpect(jsonPath("$.data.accounts[0].groups[0].isAdmin").value(false));
    }

    @Test
    void putMarketingTemplate_updatesTaskReferencedTemplate() throws Exception {
        Fixture fixture = seedFixture("controller-material-update");
        long taskId = createTask("Controller修改素材任务", fixture);
        MarketingTemplateDTO request = new MarketingTemplateDTO(
                "营销模板-controller-material-update",
                1,
                "PROMO",
                null,
                "Controller更新内容",
                "Controller更新正文",
                null,
                "https://example.com/controller",
                "Controller任务侧更新素材");

        mockMvc.perform(put("/api/marketing-tasks/{id}/marketing-template", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .header(TENANT_HEADER, TENANT_CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(fixture.templateId()))
                .andExpect(jsonPath("$.data.content").value("Controller更新内容"))
                .andExpect(jsonPath("$.data.bodyText").value("Controller更新正文"));
    }

    private long createTask(String taskName, Fixture fixture) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/marketing-tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(taskName, fixture)))
                        .header(TENANT_HEADER, TENANT_CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("id").asLong();
    }

    private CreateMarketingTaskDTO request(String taskName, Fixture fixture) {
        return new CreateMarketingTaskDTO(
                taskName,
                fixture.accountGroupId(),
                "营销账号组",
                fixture.templateId(),
                "营销模板",
                "PENDING",
                1,
                30,
                true,
                true,
                false,
                "Controller测试备注",
                List.of(new MarketingSelectionDTO(fixture.accountId(), List.of(fixture.groupLinkId()))));
    }

    private Fixture seedFixture(String suffix) {
        long now = System.currentTimeMillis();
        long accountGroupId = insertAndReturnId("""
                INSERT INTO account_group (tenant_id, name, system_builtin, created_at, updated_at)
                VALUES (?, ?, 0, ?, ?)
                """, ps -> {
            ps.setLong(1, TEST_TENANT_ID);
            ps.setString(2, "营销账号组-" + suffix);
            ps.setLong(3, now);
            ps.setLong(4, now);
        });
        long templateId = insertAndReturnId("""
                INSERT INTO marketing_template
                    (tenant_id, template_name, link_mode, text_type, content, body_text, buttons, created_at, updated_at)
                VALUES (?, ?, 1, 'PROMO', '内容', '正文', NULL, ?, ?)
                """, ps -> {
            ps.setLong(1, TEST_TENANT_ID);
            ps.setString(2, "营销模板-" + suffix);
            ps.setLong(3, now);
            ps.setLong(4, now);
        });
        String phone = "923100" + Math.abs(suffix.hashCode() % 1000000);
        long accountId = insertAndReturnId("""
                INSERT INTO account
                    (tenant_id, ws_phone, account_type, ownership, account_group_id, priority, created_at, updated_at)
                VALUES (?, ?, 1, 1, ?, 0, ?, ?)
                """, ps -> {
            ps.setLong(1, TEST_TENANT_ID);
            ps.setString(2, phone);
            ps.setLong(3, accountGroupId);
            ps.setLong(4, now);
            ps.setLong(5, now);
        });
        jdbc.update("""
                INSERT INTO account_state
                    (tenant_id, account_id, account_state, login_state, risk_status, created_at, updated_at)
                VALUES (?, ?, 2, 1, 1, ?, ?)
                """, TEST_TENANT_ID, accountId, now, now);
        String groupUrl = "https://chat.whatsapp.com/" + suffix;
        long groupLinkId = insertAndReturnId("""
                INSERT INTO group_link
                    (tenant_id, link_url, group_name, origin, membership_state, created_at, updated_at)
                VALUES (?, ?, ?, 2, 2, ?, ?)
                """, ps -> {
            ps.setLong(1, TEST_TENANT_ID);
            ps.setString(2, groupUrl);
            ps.setString(3, "营销群-" + suffix);
            ps.setLong(4, now);
            ps.setLong(5, now);
        });
        String groupJid = "1203630" + Math.abs(suffix.hashCode()) + "@g.us";
        jdbc.update("""
                INSERT INTO group_link_preview
                    (tenant_id, group_link_id, group_jid, wa_subject, announce_only, created_at, updated_at)
                VALUES (?, ?, ?, ?, 0, ?, ?)
                """, TEST_TENANT_ID, groupLinkId, groupJid, "WA群-" + suffix, now, now);
        jdbc.update("""
                INSERT INTO group_link_health
                    (tenant_id, group_link_id, health_status, is_banned, created_at, updated_at)
                VALUES (?, ?, 1, 0, ?, ?)
                """, TEST_TENANT_ID, groupLinkId, now, now);
        return new Fixture(accountGroupId, templateId, accountId, phone, groupLinkId, groupJid);
    }

    private long insertAndReturnId(String sql, SqlBinder binder) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            binder.bind(ps);
            return ps;
        }, keys);
        Number key = keys.getKey();
        assertThat(key).as("generated key for " + sql).isNotNull();
        return key.longValue();
    }

    @FunctionalInterface
    private interface SqlBinder {
        void bind(PreparedStatement ps) throws java.sql.SQLException;
    }

    private record Fixture(
            long accountGroupId,
            long templateId,
            long accountId,
            String phone,
            long groupLinkId,
            String groupJid) {
    }
}
