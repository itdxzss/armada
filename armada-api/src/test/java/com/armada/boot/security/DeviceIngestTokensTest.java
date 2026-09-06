package com.armada.boot.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.boot.config.DeviceIngestConfig;
import com.armada.testsupport.DeviceImportTestData;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class DeviceIngestTokensTest {

    @Test
    void missingConfigurationFailsStartup() {
        new ApplicationContextRunner().withUserConfiguration(DeviceIngestConfig.class)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void resolvesOnlyExactTokenAndServerDefaults() {
        String token = DeviceImportTestData.token();
        DeviceIngestTokens tokens = new DeviceIngestTokens(DeviceImportTestData.clients(token, 7, 11));
        var defaults = tokens.resolve(token).orElseThrow();
        assertThat(defaults.tenantId()).isEqualTo(7);
        assertThat(defaults.metadata().accountGroupId()).isEqualTo(11);
        assertThat(defaults.metadata().importFormat()).isEqualTo(3);
        assertThat(defaults.metadata().deviceOs()).isEqualTo(2);
        assertThat(defaults.metadata().accountType()).isEqualTo(2);
        assertThat(defaults.metadata().ipAllocationMode()).isEqualTo("mixed");
        assertThat(tokens.resolve(DeviceImportTestData.token())).isEmpty();
        assertThat(tokens.resolve(" " + token)).isEmpty();
        assertThat(tokens.resolve(null)).isEmpty();
        assertThat(tokens.toString().contains(token)).isFalse();
    }

    @Test
    void malformedSecretConfigurationDoesNotLeakThroughStartupException() {
        String token = DeviceImportTestData.token();
        new ApplicationContextRunner().withUserConfiguration(DeviceIngestConfig.class)
                .withPropertyValues("ARMADA_DEVICE_INGEST_CLIENTS_JSON=" + token)
                .run(context -> {
                    assertThat(context).hasFailed();
                    StringWriter output = new StringWriter();
                    context.getStartupFailure().printStackTrace(new PrintWriter(output));
                    assertThat(output.toString().contains(token)).isFalse();
                    assertThat(output.toString()).contains("ARMADA_DEVICE_INGEST_CLIENTS_JSON");
                });
    }

    @Test
    void invalidAndDuplicateMappingsFailClosed() {
        String token = DeviceImportTestData.token();
        String valid = DeviceImportTestData.clients(token, 7, 11);
        for (String invalid : new String[]{"", "[]", "{}", valid + " {}",
                valid.replace("\"tenantId\":7", "\"tenantId\":0"),
                valid.replace("\"deviceOs\":2", "\"deviceOs\":3"),
                valid.replace("\"accountType\":2", "\"accountType\":null"),
                valid.replace("\"accountGroupId\":11", "\"accountGroupId\":\"11\""),
                valid.replace("mixed", "invalid"), valid.replace(token, "too-short"),
                valid.substring(0, valid.length() - 1) + "," + valid.substring(1)}) {
            assertThatThrownBy(() -> new DeviceIngestTokens(invalid))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ARMADA_DEVICE_INGEST_CLIENTS_JSON");
        }
    }
}
