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
    void six_isRejected() {
        assertThatThrownBy(() -> parser.parse(ImportFormat.SIX, null, "x,x,x,x,x,x"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("六段暂不支持");
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
