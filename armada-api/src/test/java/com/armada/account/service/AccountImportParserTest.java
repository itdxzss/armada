package com.armada.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.account.model.entity.ImportFormat;
import com.armada.account.model.entity.ParsedEntry;
import com.armada.shared.exception.BusinessException;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

/**
 * AccountImportParser 业务逻辑单测。
 * 纯内存,无 DB;验证解析规则和完整性门槛。
 */
class AccountImportParserTest {

    private final AccountImportParser parser = new AccountImportParser();

    // ---- JSON 格式:完整性校验 ----

    @Test
    void json_missingRegistrationId_marksIncomplete() {
        String json = "[{\"wid\":\"8613800138000\",\"noiseKey\":{},\"signedIdentityKey\":{},\"signedPreKey\":{}}]";
        List<ParsedEntry> entries = parser.parse(ImportFormat.JSON, null, json);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getParseError()).contains("凭据不全").contains("registrationId");
    }

    @Test
    void json_complete_parsesOk() {
        String json = nakedCreds("8613800138000");
        List<ParsedEntry> entries = parser.parse(ImportFormat.JSON, null, json);
        assertThat(entries.get(0).getParseError()).isNull();
        assertThat(entries.get(0).getWid()).isEqualTo("8613800138000");
    }

    @Test
    void six_zhuanOrder_normalizesSemanticCredentialFields() {
        String line = "919000000001,static-pub,static-pri,identity-pub,identity-pri,phone-id";

        List<ParsedEntry> entries = parser.parse(ImportFormat.SIX, null, line);

        assertThat(entries).hasSize(1);
        ParsedEntry entry = entries.get(0);
        assertThat(entry.getParseError()).isNull();
        assertThat(entry.getWid()).isEqualTo("919000000001");
        assertThat(entry.getRawPayload()).isEqualTo(line);
        assertThat(entry.getSourceEntryName()).isEqualTo("six-input[1]");
        assertThat(entry.getData().get("phone").asText()).isEqualTo("919000000001");
        assertThat(entry.getData().get("static_pub_key").asText()).isEqualTo("static-pub");
        assertThat(entry.getData().get("static_pri_key").asText()).isEqualTo("static-pri");
        assertThat(entry.getData().get("id_pub_key").asText()).isEqualTo("identity-pub");
        assertThat(entry.getData().get("id_pri_key").asText()).isEqualTo("identity-pri");
        assertThat(entry.getData().get("phone_id").asText()).isEqualTo("phone-id");
        assertThat(entry.getData().has("device_identity_key")).isFalse();
    }

    @Test
    void six_trimsSemanticValuesAndPreservesOriginalRawPayload() {
        String line = " 919000000001 , static-pub , static-pri , identity-pub , identity-pri , phone-id ";

        List<ParsedEntry> entries = parser.parse(ImportFormat.SIX, null, line);

        assertThat(entries).hasSize(1);
        ParsedEntry entry = entries.get(0);
        assertThat(entry.getParseError()).isNull();
        assertThat(entry.getRawPayload()).isEqualTo(line);
        assertThat(entry.getWid()).isEqualTo("919000000001");
        assertThat(entry.getData().get("static_pub_key").asText()).isEqualTo("static-pub");
        assertThat(entry.getData().get("static_pri_key").asText()).isEqualTo("static-pri");
        assertThat(entry.getData().get("id_pub_key").asText()).isEqualTo("identity-pub");
        assertThat(entry.getData().get("id_pri_key").asText()).isEqualTo("identity-pri");
        assertThat(entry.getData().get("phone_id").asText()).isEqualTo("phone-id");
    }

    @Test
    void six_fiveColumns_generatesPhoneIdAndPreservesOriginalRawPayload() {
        String line = "919000000101,static-pub,static-pri,identity-pub,identity-pri";

        ParsedEntry entry = parser.parse(ImportFormat.SIX, null, line).get(0);

        assertThat(entry.getParseError()).isNull();
        assertThat(entry.getWid()).isEqualTo("919000000101");
        assertThat(entry.getRawPayload()).isEqualTo(line);
        assertThat(entry.getData().get("phone_id").asText()).matches("[0-9a-f]{32}");
        assertThat(entry.getData().get("static_pub_key").asText()).isEqualTo("static-pub");
        assertThat(entry.getData().get("static_pri_key").asText()).isEqualTo("static-pri");
        assertThat(entry.getData().get("id_pub_key").asText()).isEqualTo("identity-pub");
        assertThat(entry.getData().get("id_pri_key").asText()).isEqualTo("identity-pri");
    }

    @Test
    void six_multipleFiveColumnRows_generateUniquePhoneIds() {
        String text = "919000000102,a,b,c,d\n919000000103,e,f,g,h";

        List<ParsedEntry> entries = parser.parse(ImportFormat.SIX, null, text);

        assertThat(entries).hasSize(2);
        assertThat(entries).allSatisfy(entry -> {
            assertThat(entry.getParseError()).isNull();
            assertThat(entry.getData().get("phone_id").asText()).matches("[0-9a-f]{32}");
        });
        assertThat(entries)
                .extracting(entry -> entry.getData().get("phone_id").asText())
                .doesNotHaveDuplicates();
    }

    @Test
    void six_nonFiveOrSixColumnCount_marksRowFailed() {
        List<ParsedEntry> fourColumns = parser.parse(
                ImportFormat.SIX, null, "919000000104,static-pub,static-pri,identity-pub");
        List<ParsedEntry> sevenColumns = parser.parse(
                ImportFormat.SIX, null,
                "919000000105,static-pub,static-pri,identity-pub,identity-pri,phone-id,extra");

        assertThat(fourColumns.get(0).getParseError()).contains("应为5列或6列");
        assertThat(sevenColumns.get(0).getParseError()).contains("应为5列或6列");
    }

    @Test
    void six_emptyPhoneId_marksRowFailed() {
        List<ParsedEntry> entries = parser.parse(
                ImportFormat.SIX,
                null,
                "919000000001,static-pub,static-pri,identity-pub,identity-pri,");

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getParseError()).contains("第6列为空");
    }

    @Test
    void six_invalidPhone_marksRowFailed() {
        List<ParsedEntry> entries = parser.parse(
                ImportFormat.SIX,
                null,
                "not-a-phone,static-pub,static-pri,identity-pub,identity-pri,phone-id");

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getParseError()).contains("wid 不合法");
    }

    @Test
    void six_emptyPhoneId_errorDoesNotEchoCredentialValues() {
        String fakePrivateValue = "fake-private-value-that-must-not-leak";
        List<ParsedEntry> entries = parser.parse(
                ImportFormat.SIX,
                null,
                "919000000001,static-pub," + fakePrivateValue + ",identity-pub,identity-pri,");

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getParseError())
                .contains("第6列为空")
                .doesNotContain(fakePrivateValue);
    }

    // ---- JSON 格式:其他完整性键 ----

    @Test
    void json_missingNoiseKey_marksIncomplete() {
        String json = "[{\"wid\":\"8613800138000\",\"registrationId\":7,\"signedIdentityKey\":{},\"signedPreKey\":{}}]";
        List<ParsedEntry> entries = parser.parse(ImportFormat.JSON, null, json);
        assertThat(entries.get(0).getParseError()).contains("凭据不全").contains("noiseKey");
    }

    @Test
    void json_missingSignedIdentityKey_marksIncomplete() {
        String json = "[{\"wid\":\"8613800138000\",\"registrationId\":7,\"noiseKey\":{},\"signedPreKey\":{}}]";
        List<ParsedEntry> entries = parser.parse(ImportFormat.JSON, null, json);
        assertThat(entries.get(0).getParseError()).contains("凭据不全").contains("signedIdentityKey");
    }

    @Test
    void json_missingSignedPreKey_marksIncomplete() {
        String json = "[{\"wid\":\"8613800138000\",\"registrationId\":7,\"noiseKey\":{},\"signedIdentityKey\":{}}]";
        List<ParsedEntry> entries = parser.parse(ImportFormat.JSON, null, json);
        assertThat(entries.get(0).getParseError()).contains("凭据不全").contains("signedPreKey");
    }

    @Test
    void json_wrappedCreds_marksIncomplete() {
        String json = "[{\"wid\":\"8613800138000\",\"creds\":{\"registrationId\":7,\"noiseKey\":{},\"signedIdentityKey\":{},\"signedPreKey\":{}}}]";
        List<ParsedEntry> entries = parser.parse(ImportFormat.JSON, null, json);
        assertThat(entries.get(0).getParseError()).contains("凭据不全").contains("缺");
    }

    // ---- JSON 格式:wid 抠取路径 ----

    @Test
    void json_widFromTopLevelPhone() {
        String json = "[{\"phone\":\"8613912345678\",\"registrationId\":1,\"noiseKey\":{},\"signedIdentityKey\":{},\"signedPreKey\":{}}]";
        List<ParsedEntry> entries = parser.parse(ImportFormat.JSON, null, json);
        assertThat(entries.get(0).getWid()).isEqualTo("8613912345678");
    }

    @Test
    void json_widFromTopLevelPhoneUppercase() {
        String json = "[{\"Phone\":\"8613912345678\",\"registrationId\":1,\"noiseKey\":{},\"signedIdentityKey\":{},\"signedPreKey\":{}}]";
        List<ParsedEntry> entries = parser.parse(ImportFormat.JSON, null, json);
        assertThat(entries.get(0).getWid()).isEqualTo("8613912345678");
    }

    @Test
    void json_widFromTopLevelMeId() {
        // me.id at top level
        String json = "[{\"me\":{\"id\":\"8613800138000:7@s.whatsapp.net\"},\"registrationId\":1,\"noiseKey\":{},\"signedIdentityKey\":{},\"signedPreKey\":{}}]";
        List<ParsedEntry> entries = parser.parse(ImportFormat.JSON, null, json);
        assertThat(entries.get(0).getWid()).isEqualTo("8613800138000");
    }

    // ---- JSON 格式:单对象 vs 数组 ----

    @Test
    void json_singleObject_parsesOk() {
        String json = nakedCredsObject("8613800138000");
        List<ParsedEntry> entries = parser.parse(ImportFormat.JSON, null, json);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getWid()).isEqualTo("8613800138000");
    }

    @Test
    void json_multipleInArray_parsesAll() {
        String json = "[" + nakedCredsObject("8613800138001") + "," + nakedCredsObject("8613800138002") + "]";
        List<ParsedEntry> entries = parser.parse(ImportFormat.JSON, null, json);
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).getWid()).isEqualTo("8613800138001");
        assertThat(entries.get(1).getWid()).isEqualTo("8613800138002");
    }

    // ---- JSON 格式:zip 包 ----

    @Test
    void json_zipWithOneFile_parsesOk() throws Exception {
        String entryJson = nakedCredsObject("8613800138000");
        byte[] zipBytes = buildZip("8613800138000.json", entryJson.getBytes());
        List<ParsedEntry> entries = parser.parse(ImportFormat.JSON, zipBytes, null);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getWid()).isEqualTo("8613800138000");
        assertThat(entries.get(0).getParseError()).isNull();
    }

    @Test
    void json_zipPreservesRawPayloadAndEntryName() throws Exception {
        String entryJson = nakedCredsObject("8613800138999");
        byte[] zipBytes = buildZip("folder/8613800138999.json", entryJson.getBytes());

        List<ParsedEntry> entries = parser.parse(ImportFormat.JSON, zipBytes, null);

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getRawPayload()).isEqualTo(entryJson);
        assertThat(entries.get(0).getSourceEntryName()).isEqualTo("folder/8613800138999.json");
    }

    @Test
    void json_arrayTextPreservesEachElementPayload() {
        String first = nakedCredsObject("8613800138101");
        String second = nakedCredsObject("8613800138102");

        List<ParsedEntry> entries = parser.parse(ImportFormat.JSON, null, "[" + first + "," + second + "]");

        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).getRawPayload()).contains("\"8613800138101\"");
        assertThat(entries.get(0).getSourceEntryName()).isEqualTo("text-input[0]");
        assertThat(entries.get(1).getRawPayload()).contains("\"8613800138102\"");
        assertThat(entries.get(1).getSourceEntryName()).isEqualTo("text-input[1]");
    }

    @Test
    void json_zipArrayEntryPreservesEachElementPayloadAndEntryName() throws Exception {
        String first = nakedCredsObject("8613800138301");
        String second = nakedCredsObject("8613800138302");
        byte[] zipBytes = buildZip(
                "folder/accounts.json",
                ("[" + first + "," + second + "]").getBytes(java.nio.charset.StandardCharsets.UTF_8));

        List<ParsedEntry> entries = parser.parse(ImportFormat.JSON, zipBytes, null);

        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).getRawPayload()).contains("8613800138301");
        assertThat(entries.get(0).getSourceEntryName()).isEqualTo("folder/accounts.json[0]");
        assertThat(entries.get(1).getRawPayload()).contains("8613800138302");
        assertThat(entries.get(1).getSourceEntryName()).isEqualTo("folder/accounts.json[1]");
    }

    @Test
    void json_zipWithMultipleFiles_parsesAll() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            String e1 = nakedCredsObject("8613800138001");
            String e2 = nakedCredsObject("8613800138002");
            zos.putNextEntry(new ZipEntry("acc1.json"));
            zos.write(e1.getBytes());
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("acc2.json"));
            zos.write(e2.getBytes());
            zos.closeEntry();
        }
        List<ParsedEntry> entries = parser.parse(ImportFormat.JSON, baos.toByteArray(), null);
        assertThat(entries).hasSize(2);
    }

    @Test
    void json_zipNonJsonEntriesSkipped() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            String e1 = nakedCredsObject("8613800138001");
            zos.putNextEntry(new ZipEntry("acc1.json"));
            zos.write(e1.getBytes());
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("readme.txt"));
            zos.write("ignore me".getBytes());
            zos.closeEntry();
        }
        List<ParsedEntry> entries = parser.parse(ImportFormat.JSON, baos.toByteArray(), null);
        // 只解析 .json 条目
        assertThat(entries).hasSize(1);
    }

    // ---- PARAMS 格式 ----

    @Test
    void params_validWid_parsesOk() {
        String json = "[{\"wid\":\"8613800138000\"}]";
        List<ParsedEntry> entries = parser.parse(ImportFormat.PARAMS, null, json);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getParseError()).isNull();
        assertThat(entries.get(0).getWid()).isEqualTo("8613800138000");
    }

    @Test
    void params_arrayTextPreservesRawPayloadAndEntryName() {
        List<ParsedEntry> entries = parser.parse(
                ImportFormat.PARAMS,
                null,
                "[{\"wid\":\"8613800138201\"},{\"wid\":\"8613800138202\"}]");

        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).getRawPayload()).contains("\"8613800138201\"");
        assertThat(entries.get(0).getSourceEntryName()).isEqualTo("params-input[0]");
        assertThat(entries.get(1).getRawPayload()).contains("\"8613800138202\"");
        assertThat(entries.get(1).getSourceEntryName()).isEqualTo("params-input[1]");
    }

    @Test
    void params_missingWid_marksError() {
        String json = "[{\"phone\":\"8613800138000\"}]";
        List<ParsedEntry> entries = parser.parse(ImportFormat.PARAMS, null, json);
        assertThat(entries.get(0).getParseError()).isNotNull().contains("wid");
    }

    @Test
    void params_invalidWid_marksError() {
        // wid 不是合法手机号
        String json = "[{\"wid\":\"abc\"}]";
        List<ParsedEntry> entries = parser.parse(ImportFormat.PARAMS, null, json);
        assertThat(entries.get(0).getParseError()).isNotNull().contains("wid");
    }

    // ---- 非法 JSON ----

    @Test
    void json_invalidJson_marksParseError() {
        String json = "[{not valid json}]";
        List<ParsedEntry> entries = parser.parse(ImportFormat.JSON, null, json);
        // 整体非法 → 应返回带 parseError 的单条,而不是抛出
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getParseError()).isNotNull();
    }

    @Test
    void json_invalidTextKeepsRawPayloadForFailureExport() {
        String invalid = "[{not valid json}]";

        List<ParsedEntry> entries = parser.parse(ImportFormat.JSON, null, invalid);

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getParseError()).isNotNull();
        assertThat(entries.get(0).getRawPayload()).isEqualTo(invalid);
        assertThat(entries.get(0).getSourceEntryName()).isEqualTo("text-input");
    }

    // ---- ImportFormat enum ----

    @Test
    void importFormat_fromCode_works() {
        assertThat(ImportFormat.fromCode(1)).isEqualTo(ImportFormat.SIX);
        assertThat(ImportFormat.fromCode(2)).isEqualTo(ImportFormat.JSON);
        assertThat(ImportFormat.fromCode(3)).isEqualTo(ImportFormat.PARAMS);
    }

    @Test
    void importFormat_fromCode_unknownThrows() {
        assertThatThrownBy(() -> ImportFormat.fromCode(99))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未知导入格式编码");
    }

    // ---- 工具方法 ----

    private byte[] buildZip(String entryName, byte[] content) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry(entryName));
            zos.write(content);
            zos.closeEntry();
        }
        return baos.toByteArray();
    }

    private static String nakedCreds(String wid) {
        return "[" + nakedCredsObject(wid) + "]";
    }

    private static String nakedCredsObject(String wid) {
        return "{\"wid\":\"" + wid
                + "\",\"registrationId\":1,\"noiseKey\":{},\"signedIdentityKey\":{},\"signedPreKey\":{}}";
    }
}
