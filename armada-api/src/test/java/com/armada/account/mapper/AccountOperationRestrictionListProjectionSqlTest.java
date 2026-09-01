package com.armada.account.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** 锁定账号列表两类业务风控截止时间的独立投影。 */
class AccountOperationRestrictionListProjectionSqlTest {

    @Test
    void selectPageProjectsMessageAndPullingDeadlinesSeparately() throws IOException {
        try (var stream = getClass().getResourceAsStream(
                "/mapper/account/AccountMapper.xml")) {
            assertThat(stream).isNotNull();
            String xml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(xml).contains(
                    "AS messageRestrictionUntil",
                    "s.pulling_restriction_until AS pullingRestrictionUntil",
                    "s.platform_message_restriction_active = 1");
        }
    }
}
