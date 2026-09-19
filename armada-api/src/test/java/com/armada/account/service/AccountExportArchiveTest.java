package com.armada.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.account.model.dto.AccountExportMaterial;
import com.armada.account.model.entity.ImportFormat;
import com.armada.shared.exception.BusinessException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;

/** 原格式及精确选中集合的 ZIP 合约。所有材料均为合成测试数据。 */
class AccountExportArchiveTest {
    @Test
    void mixedFormatsPreservePayloadAndNeverUseSourcePaths() throws Exception {
        var result = new AccountExportArchive().build(List.of(
                new AccountExportMaterial(11L, 1, 1, "111,a,b,c,d,e"),
                new AccountExportMaterial(12L, 3, 1, "{\"android\":true}"),
                new AccountExportMaterial(13L, 3, 2, "{\"ios\":true}"),
                new AccountExportMaterial(14L, 2, 1, "{\"a\":1}"),
                new AccountExportMaterial(15L, 2, 1, "{\"b\":2}")));
        assertThat(unzip(result)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "六段.txt", "111,a,b,c,d,e",
                "全参-Android.txt", "{\"android\":true}",
                "全参-iOS.txt", "{\"ios\":true}",
                "JSON/14.json", "{\"a\":1}", "JSON/15.json", "{\"b\":2}"));
    }

    @Test
    void fiveSegmentsAreNotInventedIntoSix() throws Exception {
        var archive = new AccountExportArchive();
        assertThat(unzip(archive.build(List.of(
                new AccountExportMaterial(1L, 1, 1, "111,a,b,c,d"),
                new AccountExportMaterial(2L, 1, 1, "222,a,b,c,d,e")))))
                .containsEntry("五段.txt", "111,a,b,c,d")
                .containsEntry("六段.txt", "222,a,b,c,d,e");
    }

    @Test
    void missingUnknownDuplicateAndInvalidJsonFailClosed() {
        var archive = new AccountExportArchive();
        for (var material : List.of(new AccountExportMaterial(1L, 1, 1, null),
                new AccountExportMaterial(1L, 9, 1, "x"),
                new AccountExportMaterial(1L, 2, 1, "{}{}"))) {
            assertThatThrownBy(() -> archive.build(List.of(material))).isInstanceOf(BusinessException.class);
        }
        var row = new AccountExportMaterial(1L, 2, 1, "{}");
        assertThatThrownBy(() -> archive.build(List.of(row, row))).isInstanceOf(BusinessException.class);
    }

    @Test
    void exportedFilesCanBeParsedAgainWithoutAddingUnselectedAccounts() throws Exception {
        String six = "15550000001,a,b,c,d,e";
        String json = "{\"Phone\":\"15550000002\",\"registrationId\":1,\"noiseKey\":{},\"signedIdentityKey\":{},\"signedPreKey\":{}}";
        var files = unzip(new AccountExportArchive().build(List.of(
                new AccountExportMaterial(1L, 1, 1, six), new AccountExportMaterial(2L, 2, 1, json))));
        var parser = new AccountImportParser();
        var sixRows = parser.parse(ImportFormat.SIX, 1, 1, null, files.get("六段.txt"));
        var jsonRows = parser.parse(ImportFormat.JSON, 1, 1, null, files.get("JSON/2.json"));
        assertThat(sixRows).singleElement().satisfies(row -> {
            assertThat(row.getWid()).isEqualTo("15550000001");
            assertThat(row.getRawPayload()).isEqualTo(six);
            assertThat(row.getParseError()).isNull();
        });
        assertThat(jsonRows).singleElement().satisfies(row -> {
            assertThat(row.getWid()).isEqualTo("15550000002");
            assertThat(row.getRawPayload()).isEqualTo(json);
            assertThat(row.getParseError()).isNull();
        });
    }

    private Map<String, String> unzip(byte[] bytes) throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }
}
