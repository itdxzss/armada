package com.armada.resource.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.resource.controller.GroupDataPackageController;
import com.armada.resource.service.impl.GroupDataPackageServiceImpl;
import com.armada.task.service.PullTaskMaterialTxtParser;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 四表边界、菜单权限和大文件解析兼容合同。 */
class GroupDataPackageContractTest {
    @Test
    void migrationCreatesOnlyFourResourceTablesAndIndependentPermissions() throws Exception {
        String sql = new String(new ClassPathResource("db/migration/V191__group_data_package.sql")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(sql.split("CREATE TABLE IF NOT EXISTS", -1)).hasSize(5);
        assertThat(sql).contains("group_data_package_phone", "group_data_package_import", "group_data_package_stat",
                "tenant:group_data_package:view", "tenant:group_data_package:export",
                "resource/group-data-package/index", "mode TINYINT", "allocation_version BIGINT",
                "'ep:files',sort_no + 1,1");
        assertThat(sql).doesNotContain("click_uv", "click_pv", "CREATE TABLE pull_task", "tenant:group_data:");
        assertThat(GroupDataPackageController.class.getAnnotation(PreAuthorize.class).value())
                .isEqualTo("hasAuthority('tenant:group_data_package:view')");
    }

    @Test
    void destructiveMutationsRecheckClaimsAfterTakingThePackageLock() throws Exception {
        for (String method : new String[]{"resetFailed", "delete"}) {
            assertThat(GroupDataPackageServiceImpl.class.getMethod(method, long.class)
                    .getAnnotation(Transactional.class).isolation()).isEqualTo(Isolation.READ_COMMITTED);
        }
    }

    @Test
    void multipartDefaultsToAppendAndSixtyDaysWithoutUnconfirmedOptions() {
        var form = new GroupDataPackageController.ImportForm();
        assertThat(form.getMode()).isEqualTo("append");
        assertThat(form.getPrivacyFilterDays()).isEqualTo(60);
    }

    @Test
    void adminDuplicateAcrossExistingTwentyThousandLineBoundaryKeepsFirstPosition() {
        StringBuilder text = new StringBuilder("639171234567\n");
        for (int index = 1; index < 20_000; index++) { text.append(63_900_000_000L + index).append('\n'); }
        text.append("639171234567a\n");
        var result = new GroupDataPackageFileParser(new PullTaskMaterialTxtParser())
                .parse("跨段.txt", text.toString().getBytes(StandardCharsets.UTF_8));
        assertThat(result.phones()).hasSize(20_000);
        assertThat(result.duplicatedRows()).isEqualTo(1);
        assertThat(result.phones().get(0).adminRequired()).isTrue();
        assertThat(result.phones().get(0).sourceLineNo()).isEqualTo(1);
    }

    @Test
    void invalidUtf8IsRejectedWithoutSilentlyReplacingDigits() {
        assertThatThrownBy(() -> new GroupDataPackageFileParser(new PullTaskMaterialTxtParser())
                .parse("坏编码.txt", new byte[]{(byte) 0xc3, (byte) 0x28}))
                .hasMessageContaining("UTF-8");
    }
}
