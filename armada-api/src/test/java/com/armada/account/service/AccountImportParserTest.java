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
        List<ParsedEntry> entries = parser.parse(ImportFormat.JSON, null, null, null, json);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getParseError()).contains("凭据不全").contains("registrationId");
    }

    @Test
    void json_complete_parsesOk() {
        String json = nakedCreds("8613800138000");
        List<ParsedEntry> entries = parser.parse(ImportFormat.JSON, null, null, null, json);
        assertThat(entries.get(0).getParseError()).isNull();
        assertThat(entries.get(0).getWid()).isEqualTo("8613800138000");
    }

    @Test
    void six_zhuanOrder_normalizesSemanticCredentialFields() {
        String line = "919000000001,static-pub,static-pri,identity-pub,identity-pri,phone-id";

        List<ParsedEntry> entries = parser.parse(ImportFormat.SIX, null, null, null, line);

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

        List<ParsedEntry> entries = parser.parse(ImportFormat.SIX, null, null, null, line);

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

        ParsedEntry entry = parser.parse(ImportFormat.SIX, null, null, null, line).get(0);

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

        List<ParsedEntry> entries = parser.parse(ImportFormat.SIX, null, null, null, text);

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
                ImportFormat.SIX, null, null, null,
                "919000000104,static-pub,static-pri,identity-pub");
        List<ParsedEntry> sevenColumns = parser.parse(
                ImportFormat.SIX, null, null, null,
                "919000000105,static-pub,static-pri,identity-pub,identity-pri,phone-id,extra");

        assertThat(fourColumns.get(0).getParseError()).contains("应为5列或6列");
        assertThat(sevenColumns.get(0).getParseError()).contains("应为5列或6列");
    }

    @Test
    void six_emptyPhoneId_marksRowFailed() {
        List<ParsedEntry> entries = parser.parse(
                ImportFormat.SIX, null, null,
                null,
                "919000000001,static-pub,static-pri,identity-pub,identity-pri,");

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getParseError()).contains("第6列为空");
    }

    @Test
    void six_invalidPhone_marksRowFailed() {
        List<ParsedEntry> entries = parser.parse(
                ImportFormat.SIX, null, null,
                null,
                "not-a-phone,static-pub,static-pri,identity-pub,identity-pri,phone-id");

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getParseError()).contains("wid 不合法");
    }

    @Test
    void six_emptyPhoneId_errorDoesNotEchoCredentialValues() {
        String fakePrivateValue = "fake-private-value-that-must-not-leak";
        List<ParsedEntry> entries = parser.parse(
                ImportFormat.SIX, null, null,
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
        List<ParsedEntry> entries = parser.parse(ImportFormat.JSON, null, null, null, json);
        assertThat(entries.get(0).getParseError()).contains("凭据不全").contains("noiseKey");
    }

    @Test
    void json_missingSignedIdentityKey_marksIncomplete() {
        String json = "[{\"wid\":\"8613800138000\",\"registrationId\":7,\"noiseKey\":{},\"signedPreKey\":{}}]";
        List<ParsedEntry> entries = parser.parse(ImportFormat.JSON, null, null, null, json);
        assertThat(entries.get(0).getParseError()).contains("凭据不全").contains("signedIdentityKey");
    }

    @Test
    void json_missingSignedPreKey_marksIncomplete() {
        String json = "[{\"wid\":\"8613800138000\",\"registrationId\":7,\"noiseKey\":{},\"signedIdentityKey\":{}}]";
        List<ParsedEntry> entries = parser.parse(ImportFormat.JSON, null, null, null, json);
        assertThat(entries.get(0).getParseError()).contains("凭据不全").contains("signedPreKey");
    }

    @Test
    void json_wrappedCreds_marksIncomplete() {
        String json = "[{\"wid\":\"8613800138000\",\"creds\":{\"registrationId\":7,\"noiseKey\":{},\"signedIdentityKey\":{},\"signedPreKey\":{}}}]";
        List<ParsedEntry> entries = parser.parse(ImportFormat.JSON, null, null, null, json);
        assertThat(entries.get(0).getParseError()).contains("凭据不全").contains("缺");
    }

    // ---- JSON 格式:wid 抠取路径 ----

    @Test
    void json_widFromTopLevelPhone() {
        String json = "[{\"phone\":\"8613912345678\",\"registrationId\":1,\"noiseKey\":{},\"signedIdentityKey\":{},\"signedPreKey\":{}}]";
        List<ParsedEntry> entries = parser.parse(ImportFormat.JSON, null, null, null, json);
        assertThat(entries.get(0).getWid()).isEqualTo("8613912345678");
    }

    @Test
    void json_widFromTopLevelPhoneUppercase() {
        String json = "[{\"Phone\":\"8613912345678\",\"registrationId\":1,\"noiseKey\":{},\"signedIdentityKey\":{},\"signedPreKey\":{}}]";
        List<ParsedEntry> entries = parser.parse(ImportFormat.JSON, null, null, null, json);
        assertThat(entries.get(0).getWid()).isEqualTo("8613912345678");
    }

    @Test
    void json_widFromTopLevelMeId() {
        // me.id at top level
        String json = "[{\"me\":{\"id\":\"8613800138000:7@s.whatsapp.net\"},\"registrationId\":1,\"noiseKey\":{},\"signedIdentityKey\":{},\"signedPreKey\":{}}]";
        List<ParsedEntry> entries = parser.parse(ImportFormat.JSON, null, null, null, json);
        assertThat(entries.get(0).getWid()).isEqualTo("8613800138000");
    }

    // ---- JSON 格式:单对象 vs 数组 ----

    @Test
    void json_singleObject_parsesOk() {
        String json = nakedCredsObject("8613800138000");
        List<ParsedEntry> entries = parser.parse(ImportFormat.JSON, null, null, null, json);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getWid()).isEqualTo("8613800138000");
    }

    @Test
    void json_multipleInArray_parsesAll() {
        String json = "[" + nakedCredsObject("8613800138001") + "," + nakedCredsObject("8613800138002") + "]";
        List<ParsedEntry> entries = parser.parse(ImportFormat.JSON, null, null, null, json);
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).getWid()).isEqualTo("8613800138001");
        assertThat(entries.get(1).getWid()).isEqualTo("8613800138002");
    }

    // ---- JSON 格式:zip 包 ----

    @Test
    void json_zipWithOneFile_parsesOk() throws Exception {
        String entryJson = nakedCredsObject("8613800138000");
        byte[] zipBytes = buildZip("8613800138000.json", entryJson.getBytes());
        List<ParsedEntry> entries = parser.parse(ImportFormat.JSON, null, null, zipBytes, null);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getWid()).isEqualTo("8613800138000");
        assertThat(entries.get(0).getParseError()).isNull();
    }

    @Test
    void json_zipPreservesRawPayloadAndEntryName() throws Exception {
        String entryJson = nakedCredsObject("8613800138999");
        byte[] zipBytes = buildZip("folder/8613800138999.json", entryJson.getBytes());

        List<ParsedEntry> entries = parser.parse(ImportFormat.JSON, null, null, zipBytes, null);

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getRawPayload()).isEqualTo(entryJson);
        assertThat(entries.get(0).getSourceEntryName()).isEqualTo("folder/8613800138999.json");
    }

    @Test
    void json_arrayTextPreservesEachElementPayload() {
        String first = nakedCredsObject("8613800138101");
        String second = nakedCredsObject("8613800138102");

        List<ParsedEntry> entries = parser.parse(
                ImportFormat.JSON, null, null, null, "[" + first + "," + second + "]");

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

        List<ParsedEntry> entries = parser.parse(ImportFormat.JSON, null, null, zipBytes, null);

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
        List<ParsedEntry> entries = parser.parse(
                ImportFormat.JSON, null, null, baos.toByteArray(), null);
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
        List<ParsedEntry> entries = parser.parse(
                ImportFormat.JSON, null, null, baos.toByteArray(), null);
        // 只解析 .json 条目
        assertThat(entries).hasSize(1);
    }

    // ---- PARAMS 格式 ----

    @Test
    void params_ndjsonConvertsEachFullParamsRowToSix() {
        String first = fullParams("5210000000001");
        String second = fullParams("5210000000002");
        String json = first + "\n\n" + second;

        List<ParsedEntry> entries = parser.parse(ImportFormat.PARAMS, 1, null, null, json);

        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).getParseError()).isNull();
        assertThat(entries.get(0).getWid()).isEqualTo("5210000000001");
        assertThat(entries.get(0).getRawPayload()).isEqualTo(first);
        assertThat(entries.get(0).getSourceEntryName()).isEqualTo("params-input[1]");
        assertThat(entries.get(0).getData().path("phone").asText()).isEqualTo("5210000000001");
        assertThat(entries.get(0).getData().path("static_pub_key").asText()).isEqualTo("static-pub-test");
        assertThat(entries.get(0).getData()).hasSize(6);
        assertThat(entries.get(1).getWid()).isEqualTo("5210000000002");
        assertThat(entries.get(1).getSourceEntryName()).isEqualTo("params-input[3]");
    }

    @Test
    void params_invalidLineDoesNotBlockAdjacentValidRows() {
        String json = fullParams("5210000000011") + "\n{not-json}\n" + fullParams("5210000000012");

        List<ParsedEntry> entries = parser.parse(ImportFormat.PARAMS, 1, null, null, json);

        assertThat(entries).hasSize(3);
        assertThat(entries.get(0).getParseError()).isNull();
        assertThat(entries.get(1).getParseError()).contains("JSON 解析失败");
        assertThat(entries.get(1).getRawPayload()).isEqualTo("{not-json}");
        assertThat(entries.get(2).getParseError()).isNull();
    }

    @Test
    void params_missingAndroidField_marksCredentialIncomplete() {
        String json = fullParams("5210000000021").replace(
                "\"phoneUUID\":\"phone-uuid-test\",", "");

        List<ParsedEntry> entries = parser.parse(ImportFormat.PARAMS, 1, null, null, json);

        assertThat(entries.get(0).getParseError())
                .contains("凭据不全")
                .contains("phoneUUID");
    }

    @Test
    void params_arrayContainer_isRejectedPerLine() {
        String json = "[" + fullParams("5210000000031") + "]";

        List<ParsedEntry> entries = parser.parse(ImportFormat.PARAMS, 1, null, null, json);

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getParseError()).contains("全参必须为 JSON 对象");
    }

    @Test
    void params_iosBusinessPreservesCompleteNativeCredential() {
        String json = iosNativeParams("447700900124", "smb_ios");

        List<ParsedEntry> entries = parser.parse(ImportFormat.PARAMS, 2, 2, null, json);

        assertThat(entries).singleElement().satisfies(entry -> {
            assertThat(entry.getParseError()).isNull();
            assertThat(entry.getWid()).isEqualTo("447700900124");
            assertThat(entry.getRawPayload()).isEqualTo(json);
            assertThat(entry.getData().path("platform").asText()).isEqualTo("smb_ios");
            assertThat(entry.getData().path("registrationID").asInt()).isEqualTo(1234567890);
            assertThat(entry.getData().path("signPreKeySignature").asText()).isEqualTo(base64Bytes(64));
            assertThat(entry.getData().path("supplierExtension").path("nested").asBoolean()).isTrue();
            assertThat(entry.getData()).hasSizeGreaterThan(20);
        });
    }

    @Test
    void params_iosPersonalAcceptsIosPlatform() {
        ParsedEntry entry = parser.parse(
                ImportFormat.PARAMS, 2, 1, null, iosNativeParams("447700900125", "ios")).get(0);

        assertThat(entry.getParseError()).isNull();
        assertThat(entry.getWid()).isEqualTo("447700900125");
    }

    @Test
    void params_iosPlatformMismatchDoesNotEchoPrivateValue() {
        String privateValue = "private-secret-that-must-not-leak";
        String json = iosNativeParams("447700900126", "smb_ios")
                .replace("\"pushName\":\"Test Account\"",
                        "\"pushName\":\"" + privateValue + "\"");

        ParsedEntry entry = parser.parse(ImportFormat.PARAMS, 2, 1, null, json).get(0);

        assertThat(entry.getParseError())
                .contains("platform")
                .doesNotContain(privateValue);
    }

    @Test
    void params_iosInvalidBase64NamesFieldWithoutEchoingValue() {
        String invalidValue = "invalid-private-value";
        String json = iosNativeParams("447700900127", "smb_ios")
                .replace("\"identityPrivateKey\":\"" + base64Bytes(32) + "\"",
                        "\"identityPrivateKey\":\"" + invalidValue + "\"");

        ParsedEntry entry = parser.parse(ImportFormat.PARAMS, 2, 2, null, json).get(0);

        assertThat(entry.getParseError())
                .contains("identityPrivateKey")
                .doesNotContain(invalidValue);
    }

    @Test
    void params_iosPhoneAndJidMustMatchWithoutEchoingCredential() {
        String json = iosNativeParams("447700900128", "smb_ios")
                .replace("447700900128@s.whatsapp.net", "447700900199@s.whatsapp.net");

        ParsedEntry entry = parser.parse(ImportFormat.PARAMS, 2, 2, null, json).get(0);

        assertThat(entry.getParseError())
                .contains("phone").contains("jid")
                .doesNotContain("447700900128")
                .doesNotContain("447700900199");
    }

    @Test
    void params_iosRequiresPositiveRegistrationId() {
        String json = iosNativeParams("447700900129", "smb_ios")
                .replace("\"registrationID\":1234567890", "\"registrationID\":0");

        ParsedEntry entry = parser.parse(ImportFormat.PARAMS, 2, 2, null, json).get(0);

        assertThat(entry.getParseError()).contains("registrationID").doesNotContain("1234567890");
    }

    @Test
    void params_iosRejectsWrongPrivateKeyLength() {
        String json = iosNativeParams("447700900130", "smb_ios")
                .replace("\"identityPrivateKey\":\"" + base64Bytes(32) + "\"",
                        "\"identityPrivateKey\":\"" + base64Bytes(31) + "\"");

        ParsedEntry entry = parser.parse(ImportFormat.PARAMS, 2, 2, null, json).get(0);

        assertThat(entry.getParseError()).contains("identityPrivateKey").contains("长度");
    }

    @Test
    void params_iosPlatformCannotBeImportedAsAndroidDevice() {
        ParsedEntry entry = parser.parse(
                ImportFormat.PARAMS, 1, 2, null,
                iosNativeParams("447700900131", "smb_ios")).get(0);

        assertThat(entry.getParseError()).contains("platform").contains("deviceOs");
    }

    @Test
    void params_iosRegistrationIdSupportsUnsignedIntRange() {
        String json = iosNativeParams("447700900132", "smb_ios")
                .replace("\"registrationID\":1234567890", "\"registrationID\":4294967295");

        ParsedEntry entry = parser.parse(ImportFormat.PARAMS, 2, 2, null, json).get(0);

        assertThat(entry.getParseError()).isNull();
        assertThat(entry.getData().path("registrationID").asLong()).isEqualTo(4_294_967_295L);
    }

    @Test
    void params_iosAllowsAbsentOptionalLidAndOpaqueSupplierIdentifiers() {
        String json = iosNativeParams("447700900133", "smb_ios")
                .replace("\"lid\":\"123456789012345@lid\",", "")
                .replace("\"deviceUUID\":\"opaque-device-id\",", "")
                .replace("\"identityID\":\"opaque-identity-id\",", "")
                .replace("\"pushName\":\"Test Account\",", "")
                .replace("\"roProductDevice\":\"iPhone\",", "");

        ParsedEntry entry = parser.parse(ImportFormat.PARAMS, 2, 2, null, json).get(0);

        assertThat(entry.getParseError()).isNull();
        assertThat(entry.getData().has("lid")).isFalse();
        assertThat(entry.getData().has("deviceUUID")).isFalse();
        assertThat(entry.getData().has("identityID")).isFalse();
    }

    // ---- 非法 JSON ----

    @Test
    void json_invalidJson_marksParseError() {
        String json = "[{not valid json}]";
        List<ParsedEntry> entries = parser.parse(ImportFormat.JSON, null, null, null, json);
        // 整体非法 → 应返回带 parseError 的单条,而不是抛出
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getParseError()).isNotNull();
    }

    @Test
    void json_invalidTextKeepsRawPayloadForFailureExport() {
        String invalid = "[{not valid json}]";

        List<ParsedEntry> entries = parser.parse(ImportFormat.JSON, null, null, null, invalid);

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

    private static String fullParams(String phone) {
        return "{"
                + "\"cc\":\"52\","
                + "\"in\":\"" + phone.substring(2) + "\","
                + "\"jid\":\"" + phone + "\","
                + "\"phone\":\"" + phone + "\","
                + "\"clientStaticPublicKey\":\"static-pub-test\","
                + "\"clientStaticPrivateKey\":\"static-pri-test\","
                + "\"identityPublicKey\":\"identity-pub-test\","
                + "\"identityPrivateKey\":\"identity-pri-test\","
                + "\"phoneUUID\":\"phone-uuid-test\","
                + "\"registrationID\":77,"
                + "\"signPreKeyID\":78"
                + "}";
    }

    private static String iosNativeParams(String phone, String platform) {
        String key = base64Bytes(32);
        String signature = base64Bytes(64);
        return "{"
                + "\"jid\":\"" + phone + "@s.whatsapp.net\","
                + "\"lid\":\"123456789012345@lid\","
                + "\"mcc\":\"000\",\"mnc\":\"000\","
                + "\"phone\":\"" + phone + "\","
                + "\"device\":\"iPhone_16_Plus\","
                + "\"country\":\"ID\",\"language\":\"id\","
                + "\"platform\":\"" + platform + "\","
                + "\"pushName\":\"Test Account\","
                + "\"osVersion\":\"18.5\","
                + "\"phoneUUID\":\"11111111-2222-4333-8444-555555555555\","
                + "\"deviceUUID\":\"opaque-device-id\","
                + "\"identityID\":\"opaque-identity-id\","
                + "\"manufacturer\":\"Apple\","
                + "\"signPreKeyID\":7654321,"
                + "\"osBuildNumber\":\"22F76\","
                + "\"registrationID\":1234567890,"
                + "\"edgeRoutingInfo\":\"AQIDBA==\","
                + "\"roProductDevice\":\"iPhone\","
                + "\"whatsappVersion\":\"2.26.25.77\","
                + "\"identityPublicKey\":\"" + key + "\","
                + "\"identityPrivateKey\":\"" + key + "\","
                + "\"signPreKeyPublicKey\":\"" + key + "\","
                + "\"signPreKeySignature\":\"" + signature + "\","
                + "\"signPreKeyPrivateKey\":\"" + key + "\","
                + "\"clientStaticPublicKey\":\"" + key + "\","
                + "\"clientStaticPrivateKey\":\"" + key + "\","
                + "\"supplierExtension\":{\"nested\":true}"
                + "}";
    }

    private static String base64Bytes(int length) {
        return java.util.Base64.getEncoder().encodeToString(new byte[length]);
    }
}
