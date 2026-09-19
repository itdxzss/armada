package com.armada.task.service;

import com.armada.task.model.dto.CreateJoinTaskDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 新建进群任务必须保存显式开启的群管理配置。 */
class JoinTaskAdminContractTest {
    @Test
    void acceptsAndReturnsAdminSwitch() throws Exception {
        ObjectMapper json = new ObjectMapper();
        CreateJoinTaskDTO request = json.readValue(
                "{\"name\":\"管理员测试\",\"setAdminEnabled\":true}", CreateJoinTaskDTO.class);
        assertTrue(json.valueToTree(request).path("setAdminEnabled").asBoolean());
    }

    @Test
    void acceptsCleanupSwitchAndOldRequestsLeaveItUnset() throws Exception {
        var json = new ObjectMapper();
        var request = json.readValue("{\"setAdminEnabled\":true,\"clearAdminsAndLeaveEnabled\":true}", CreateJoinTaskDTO.class);
        assertTrue(request.clearAdminsAndLeaveEnabled());
        assertNull(json.readValue("{}", CreateJoinTaskDTO.class).clearAdminsAndLeaveEnabled());
    }
}
