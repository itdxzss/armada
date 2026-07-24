package com.armada.account.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 账号领域服务依赖边界守卫测试。 */
class AccountDomainDependencyGuardTest {

    @Test
    void accountServicesDoNotImportMarketingDomainTypes() throws IOException {
        String groupService = source("com/armada/account/service/impl/AccountGroupServiceImpl.java");
        String accountService = source("com/armada/account/service/impl/AccountServiceImpl.java");

        assertThat(groupService).doesNotContain("import com.armada.marketing.");
        assertThat(accountService).doesNotContain("import com.armada.marketing.");
    }

    private String source(String relativePath) throws IOException {
        Path sourcePath = Path.of("src/main/java").resolve(relativePath);
        return Files.readString(sourcePath, StandardCharsets.UTF_8);
    }
}
