package com.armada.task.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.armada.boot.Application;
import com.armada.task.model.dto.CreateJoinTaskDTO;
import com.armada.task.model.dto.SelectedAccount;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * JoinTaskController MockMvc 全栈冒烟测试:覆盖 7 端点 + 错误 envelope(批量删后 GET 404)。
 *
 * <p>走真库(armada schema),每个测试事务回滚不留数据。{@code spring.flyway.enabled=false} 规避
 * 共享脏 checkout 中他人在途迁移与本切片解耦(join_task/join_task_result 已由 V007 建好)。</p>
 */
@SpringBootTest(classes = Application.class,
        properties = "spring.flyway.enabled=false")
@AutoConfigureMockMvc
@Transactional
@TestMethodOrder(MethodOrderer.MethodName.class)
class JoinTaskControllerDbTest {

    private static final String TENANT_HEADER = "X-Tenant-Code";
    private static final String TENANT_CODE = "demo";

    /** 两条合法 WA 群链接,供建任务入参使用。 */
    private static final String LINK1 = "https://chat.whatsapp.com/AAABBBCCC111";
    private static final String LINK2 = "https://chat.whatsapp.com/DDDEEEFFF222";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    // -----------------------------------------------------------------------
    // 工具方法
    // -----------------------------------------------------------------------

    /**
     * 构造合法建任务请求(方式一,2 条链接,3 账号,accountsPerLink=2)。
     *
     * @param name 任务名称
     * @return 入参对象
     */
    private CreateJoinTaskDTO buildValidRequest(String name) {
        return new CreateJoinTaskDTO(
                name,
                List.of(10L),
                List.of("测试分组A"),
                List.of(
                        new SelectedAccount(1L, "8610001"),
                        new SelectedAccount(2L, "8610002"),
                        new SelectedAccount(3L, "8610003")),
                LINK1 + "\n" + LINK2,
                "FIXED_ACCOUNTS_PER_LINK",
                2, null, null,
                10, 20, null, null,
                false, 0, "SKIP");
    }

    /**
     * POST 建任务并返回任务 ID。
     *
     * @param name 任务名称
     * @return 新建任务 ID
     * @throws Exception MockMvc 调用异常
     */
    private Long createTask(String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/join-tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildValidRequest(name)))
                        .header(TENANT_HEADER, TENANT_CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body).path("data").path("id").longValue();
    }

    // -----------------------------------------------------------------------
    // T1: POST /api/join-tasks — 建任务
    // -----------------------------------------------------------------------

    /**
     * 建任务:code=0 + data.id 存在 + data.total 正确 + data.status=DRAFT。
     *
     * <p>方式一 accountsPerLink=2,2 条有效链接,3 账号 → total=4。</p>
     */
    @Test
    void t1_postCreate_returnsTaskVoWithDraftStatus() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/join-tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buildValidRequest("冒烟建任务")))
                        .header(TENANT_HEADER, TENANT_CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.total").value(4))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        long id = objectMapper.readTree(body).path("data").path("id").longValue();
        assertThat(id).isPositive();
    }

    // -----------------------------------------------------------------------
    // T2: GET /api/join-tasks — 列表
    // -----------------------------------------------------------------------

    /**
     * 建任务后 GET 列表:code=0 + data.list 为数组 + data.total >= 1 + 列表确含刚建任务。
     */
    @Test
    void t2_getList_returnsPageContainingCreatedTask() throws Exception {
        long id = createTask("列表冒烟任务");

        MvcResult result = mockMvc.perform(get("/api/join-tasks")
                        .param("page", "1")
                        .param("pageSize", "20")
                        .header(TENANT_HEADER, TENANT_CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.list").isArray())
                .andReturn();

        var tree = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(tree.path("data").path("total").asLong()).isGreaterThanOrEqualTo(1);
        boolean found = false;
        for (var row : tree.path("data").path("list")) {
            if (row.path("id").asLong() == id) {
                found = true;
                break;
            }
        }
        assertThat(found).as("列表应包含刚建任务 id=" + id).isTrue();
    }

    // -----------------------------------------------------------------------
    // T3: GET /api/join-tasks/intervals — 间隔下拉
    // -----------------------------------------------------------------------

    /**
     * 建任务后 GET 间隔下拉:code=0 + data 为数组。
     */
    @Test
    void t3_getIntervals_returnsArray() throws Exception {
        createTask("间隔下拉冒烟");

        mockMvc.perform(get("/api/join-tasks/intervals")
                        .header(TENANT_HEADER, TENANT_CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isArray());
    }

    // -----------------------------------------------------------------------
    // T4: GET /api/join-tasks/{id} — 详情
    // -----------------------------------------------------------------------

    /**
     * GET 详情:code=0 + data.id 匹配 + data.selectedAccountIds 为数组(JSON 快照解析回填)。
     */
    @Test
    void t4_getDetail_returnsDetailWithParsedAccountIds() throws Exception {
        Long id = createTask("详情冒烟任务");

        mockMvc.perform(get("/api/join-tasks/{id}", id)
                        .header(TENANT_HEADER, TENANT_CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(id))
                .andExpect(jsonPath("$.data.selectedAccountIds").isArray());
    }

    // -----------------------------------------------------------------------
    // T5: PUT /api/join-tasks/{id} — 编辑
    // -----------------------------------------------------------------------

    /**
     * PUT 编辑:改名后 code=0 + data.name 为新名。
     */
    @Test
    void t5_putUpdate_returnsDetailWithNewName() throws Exception {
        Long id = createTask("原名任务");

        String newName = "改名后任务";
        CreateJoinTaskDTO updateReq = new CreateJoinTaskDTO(
                newName,
                List.of(10L),
                List.of("测试分组A"),
                List.of(
                        new SelectedAccount(1L, "8610001"),
                        new SelectedAccount(2L, "8610002")),
                LINK1,
                "FIXED_ACCOUNTS_PER_LINK",
                2, null, null,
                5, 15, null, null,
                false, 0, "SKIP");

        mockMvc.perform(put("/api/join-tasks/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq))
                        .header(TENANT_HEADER, TENANT_CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.name").value(newName));
    }

    // -----------------------------------------------------------------------
    // T6: GET /api/join-tasks/{id}/results — 明细
    // -----------------------------------------------------------------------

    /**
     * GET 明细:code=0 + data 为数组(链接原样直出,不脱敏)。
     */
    @Test
    void t6_getResults_returnsRowArray() throws Exception {
        Long id = createTask("明细冒烟任务");

        mockMvc.perform(get("/api/join-tasks/{id}/results", id)
                        .header(TENANT_HEADER, TENANT_CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isArray());
    }

    // -----------------------------------------------------------------------
    // T7: POST /api/join-tasks/batch-delete — 批量软删 + 软删后 GET 404
    // -----------------------------------------------------------------------

    /**
     * 批量软删:code=0 + data=1(删了 1 条);随后 GET 详情 code != 0(NOT_FOUND envelope)。
     */
    @Test
    void t7_batchDelete_thenGetReturnsNotFound() throws Exception {
        Long id = createTask("待删任务");

        // 批量软删
        mockMvc.perform(post("/api/join-tasks/batch-delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("ids", List.of(id))))
                        .header(TENANT_HEADER, TENANT_CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(1));

        // 软删后 GET 详情应返回业务错误(NOT_FOUND → code != 0)
        MvcResult result = mockMvc.perform(get("/api/join-tasks/{id}", id)
                        .header(TENANT_HEADER, TENANT_CODE))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        int code = objectMapper.readTree(body).path("code").intValue();
        assertThat(code).isNotEqualTo(0);
    }
}
