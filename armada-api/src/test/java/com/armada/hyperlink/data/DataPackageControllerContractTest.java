package com.armada.hyperlink.data;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.hyperlink.data.controller.DataPackageController;
import com.armada.hyperlink.data.model.dto.DataPackageQuery;
import com.armada.hyperlink.data.model.vo.DataPackageCountryOptionVO;
import com.armada.shared.security.AuthPrincipal;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/** 数据包路径、权限、逗号查询参数和 null 序列化合同测试。 */
class DataPackageControllerContractTest {

    @Test
    void controllerUsesFrozenRootAndOwnedAuthoritiesOnly() throws Exception {
        RequestMapping mapping = DataPackageController.class.getAnnotation(RequestMapping.class);
        assertThat(mapping.value()).containsExactly("/api/data-packages");
        assertThat(DataPackageController.class.getAnnotation(PreAuthorize.class).value())
                .isEqualTo("hasAuthority('tenant:hyperlink_data:view')");
        assertAuthority("create", "tenant:hyperlink_data:create");
        assertAuthority("update", "tenant:hyperlink_data:edit");
        assertAuthority("importPhones", "tenant:hyperlink_data:import");
        assertAuthority("resetFailed", "tenant:hyperlink_data:edit");
        assertThat(java.util.Arrays.stream(DataPackageController.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals("exportPhones")))
                .allSatisfy(candidate -> assertThat(candidate.getAnnotation(PreAuthorize.class).value())
                        .isEqualTo("hasAuthority('tenant:hyperlink_data:export')"));
        assertAuthority("delete", "tenant:hyperlink_data:delete");
        assertThat(method("delete").getParameterTypes())
                .containsExactly(Long.class, AuthPrincipal.class);

        assertThat(declaredEndpoints()).containsExactlyInAnyOrder(
                "GET ",
                "GET /countries",
                "GET /{id}",
                "POST ",
                "PUT /{id}",
                "POST /{id}/import",
                "GET /{id}/phones",
                "POST /{id}/reset-failed",
                "GET /{id}/export",
                "POST /export",
                "DELETE /{id}");

        String source = Files.readString(Path.of(
                "src/main/java/com/armada/hyperlink/data/controller/DataPackageController.java"));
        assertThat(source).doesNotContain("/name", "/recount");
    }

    @Test
    void countryQueryIsCommaStringAndUnknownNullFieldIsAlwaysSerialized() throws Exception {
        assertThat(DataPackageQuery.class.getDeclaredField("countryIso2s").getType())
                .isEqualTo(String.class);
        assertThat(DataPackageQuery.class.getDeclaredField("minUvPercent").getType())
                .isEqualTo(BigDecimal.class);
        assertThat(DataPackageQuery.class.getDeclaredField("maxUvPercent").getType())
                .isEqualTo(BigDecimal.class);
        ObjectMapper mapper = new ObjectMapper()
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);

        String json = mapper.writeValueAsString(
                new DataPackageCountryOptionVO("UNKNOWN", null, "未识别"));

        assertThat(json).contains("\"countryIso2\":null");
    }

    private static void assertAuthority(String methodName, String authority) {
        Method method = method(methodName);
        assertThat(method.getAnnotation(PreAuthorize.class).value())
                .isEqualTo("hasAuthority('" + authority + "')");
    }

    private static Method method(String methodName) {
        return java.util.Arrays.stream(DataPackageController.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow();
    }

    private static Set<String> declaredEndpoints() {
        Set<String> result = new LinkedHashSet<>();
        for (Method method : DataPackageController.class.getDeclaredMethods()) {
            GetMapping get = method.getAnnotation(GetMapping.class);
            PostMapping post = method.getAnnotation(PostMapping.class);
            PutMapping put = method.getAnnotation(PutMapping.class);
            DeleteMapping delete = method.getAnnotation(DeleteMapping.class);
            addMappings(result, "GET", get == null ? null : get.value());
            addMappings(result, "POST", post == null ? null : post.value());
            addMappings(result, "PUT", put == null ? null : put.value());
            addMappings(result, "DELETE", delete == null ? null : delete.value());
        }
        return result;
    }

    private static void addMappings(Set<String> result, String verb, String[] paths) {
        if (paths == null) {
            return;
        }
        if (paths.length == 0) {
            result.add(verb + " ");
            return;
        }
        for (String path : paths) {
            result.add(verb + " " + path);
        }
    }
}
