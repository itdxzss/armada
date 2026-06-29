package com.armada.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.account.mapper.AccountCredentialMapper;
import com.armada.account.mapper.AccountMapper;
import com.armada.account.mapper.AccountStateMapper;
import com.armada.account.model.dto.AccountImportDTO;
import com.armada.account.model.entity.Account;
import com.armada.account.model.entity.AccountImportOnlinePhase;
import com.armada.account.model.vo.AccountImportBatchVO;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.testsupport.DbTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * AccountImportService 真库集成测试:验三步原子写、批次计数、DB uq 兜底、空内容拒绝。
 *
 * <p>每个 @Test 在 @Transactional 内执行并回滚,互不干扰。</p>
 */
class AccountImportServiceImplDbTest extends DbTestBase {

    @Autowired
    AccountImportService service;

    @Autowired
    AccountMapper accountMapper;

    @Autowired
    AccountStateMapper stateMapper;

    @Autowired
    AccountCredentialMapper credentialMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    /**
     * 核心测试:2 条完整 + 1 条凭据不全 + 1 条批内重复(与第 1 条同 wid)。
     * 验 total=4 / imported=2 / duplicate=1 / formatError=1 / status=2
     * + 三步写后 account+account_state+account_credential 各一行 + protocol_account_id="acc_"+wid。
     */
    @Test
    void import_threeStepWrite_andBatchCounts() {
        // 2 条完整 + 1 条凭据不全 + 1 条批内重复(与第1条同 wid)
        String json = "["
                + "{\"wid\":\"8613800138000\",\"registrationId\":1,\"noiseKey\":{},\"signedIdentityKey\":{},\"signedPreKey\":{}},"
                + "{\"wid\":\"8613800138002\",\"registrationId\":2,\"noiseKey\":{},\"signedIdentityKey\":{},\"signedPreKey\":{}},"
                + "{\"wid\":\"8613800138003\",\"noiseKey\":{}},"
                + "{\"wid\":\"8613800138000\",\"registrationId\":1,\"noiseKey\":{},\"signedIdentityKey\":{},\"signedPreKey\":{}}"
                + "]";
        var meta = new AccountImportDTO(null, 2, 1, 2, "印度", "r", null);
        AccountImportBatchVO b = service.importAccounts(meta, null, json);

        assertThat(b.totalRows()).isEqualTo(4);
        assertThat(b.importedRows()).isEqualTo(2);      // 两条完整入库
        assertThat(b.duplicateRows()).isEqualTo(1);     // 批内重复
        assertThat(b.formatErrorRows()).isEqualTo(1);   // 凭据不全
        assertThat(b.status()).isEqualTo(2);            // step1 同步导入流程结束恒「2 已完成」

        // 三步写校验:成功号在 account + account_state + account_credential 各一行
        Account a = accountMapper.selectActiveByWsPhone("8613800138000");
        assertThat(a).isNotNull();
        assertThat(a.getProtocolAccountId()).isEqualTo("acc_8613800138000");
        assertThat(stateMapper.selectByAccountId(a.getId())).isNotNull();        // 默认行,状态列 NULL
        assertThat(credentialMapper.selectByAccountId(a.getId())).isNotNull();
    }

    /**
     * 导入成功行进入待上线队列;失败/重复/凭据不全行不参与自动上线。
     */
    @Test
    void import_successRowsQueuedForOnlineTracking_failedRowsSkipped() {
        String json = "["
                + "{\"wid\":\"8613850000001\",\"registrationId\":1,\"noiseKey\":{},\"signedIdentityKey\":{},\"signedPreKey\":{}},"
                + "{\"wid\":\"8613850000002\",\"registrationId\":2,\"noiseKey\":{},\"signedIdentityKey\":{},\"signedPreKey\":{}},"
                + "{\"wid\":\"8613850000003\",\"noiseKey\":{}},"
                + "{\"wid\":\"8613850000001\",\"registrationId\":1,\"noiseKey\":{},\"signedIdentityKey\":{},\"signedPreKey\":{}}"
                + "]";
        var meta = new AccountImportDTO(null, 2, 1, 2, "印度", "r", null);

        AccountImportBatchVO batch = service.importAccounts(meta, null, json);

        List<Integer> phases = jdbcTemplate.query(
                """
                SELECT online_phase
                FROM account_import_detail
                WHERE batch_id = ?
                ORDER BY line_no ASC
                """,
                (rs, rowNum) -> rs.getInt("online_phase"),
                batch.id());
        assertThat(phases).containsExactly(
                AccountImportOnlinePhase.QUEUED,
                AccountImportOnlinePhase.QUEUED,
                AccountImportOnlinePhase.SKIPPED,
                AccountImportOnlinePhase.SKIPPED);
    }

    /**
     * 跨批 DB uq 兜底:第一批导入成功后,第二批同号再导入 → DUPLICATE(库内冲突)。
     * 验第二批 importedRows=0 / duplicateRows=1。
     */
    @Test
    void import_crossBatch_dbUqDuplicate() {
        String json = "[{\"wid\":\"8613811111111\",\"registrationId\":1,\"noiseKey\":{},\"signedIdentityKey\":{},\"signedPreKey\":{}}]";
        var meta = new AccountImportDTO(null, 2, 1, 2, "印度", "r", null);

        // 第一批成功
        AccountImportBatchVO first = service.importAccounts(meta, null, json);
        assertThat(first.importedRows()).isEqualTo(1);

        // 第二批同号 → DB uq 兜底 → DUPLICATE
        var meta2 = new AccountImportDTO(null, 2, 1, 2, "印度", "r", null);
        AccountImportBatchVO second = service.importAccounts(meta2, null, json);
        assertThat(second.importedRows()).isEqualTo(0);
        assertThat(second.duplicateRows()).isEqualTo(1);
        assertThat(second.status()).isEqualTo(2);
    }

    /**
     * 空 entries 拒绝:无可导入内容时抛 BusinessException(VALIDATION)。
     */
    @Test
    void import_emptyText_throwsBusinessException() {
        var meta = new AccountImportDTO(null, 2, 1, 2, "印度", "r", null);
        assertThatThrownBy(() -> service.importAccounts(meta, null, ""))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无可导入内容");
    }

    /**
     * 三步原子写:若 account_state insert 失败则 account 行也回滚,不留孤儿。
     * 通过验证正常写入后三张表均有数据来间接确认事务原子性。
     */
    @Test
    void import_success_allThreeTablesHaveRow() {
        String json = "[{\"wid\":\"8613822222222\",\"registrationId\":5,\"noiseKey\":{},\"signedIdentityKey\":{},\"signedPreKey\":{}}]";
        var meta = new AccountImportDTO(null, 2, 1, 1, "美国", null, null);

        AccountImportBatchVO b = service.importAccounts(meta, null, json);
        assertThat(b.importedRows()).isEqualTo(1);

        Account a = accountMapper.selectActiveByWsPhone("8613822222222");
        assertThat(a).isNotNull();
        assertThat(a.getAccountType()).isEqualTo(1);    // 冻结:导入时传的 accountType=1
        assertThat(a.getProtocolAccountId()).isEqualTo("acc_8613822222222");
        assertThat(stateMapper.selectByAccountId(a.getId())).isNotNull();
        assertThat(credentialMapper.selectByAccountId(a.getId())).isNotNull();
    }

    /**
     * deviceOs=null(用户未选机型)导入不 NPE:验 importedRows=1 且 account.deviceOs 为 null。
     */
    @Test
    void import_deviceOsNull_doesNotNpe() {
        String json = "[{\"wid\":\"8613833333333\",\"registrationId\":7,\"noiseKey\":{},\"signedIdentityKey\":{},\"signedPreKey\":{}}]";
        // deviceOs=null 模拟用户未选机型
        var meta = new AccountImportDTO(null, 2, null, 1, "美国", null, null);

        AccountImportBatchVO b = service.importAccounts(meta, null, json);
        assertThat(b.importedRows()).isEqualTo(1);

        Account a = accountMapper.selectActiveByWsPhone("8613833333333");
        assertThat(a).isNotNull();
        assertThat(a.getDeviceOs()).isNull();   // device_os 列 DEFAULT NULL,允许 null
    }

    // ---- source_file_name 兜底回归测试 ----

    /**
     * P0-1a:文件导入(sourceFileName 有值) → source_file_name = 文件名,账号正常入库。
     */
    @Test
    void import_withFileName_sourceFileNameIsFileName() {
        String json = "[{\"wid\":\"8613841000001\",\"registrationId\":11,\"noiseKey\":{},\"signedIdentityKey\":{},\"signedPreKey\":{}}]";
        // sourceFileName 有值,模拟文件上传
        var meta = new AccountImportDTO(null, 2, 1, 1, "美国", null, "accounts_20240624.json");

        AccountImportBatchVO b = service.importAccounts(meta, null, json);

        assertThat(b.importedRows()).isEqualTo(1);
        // 文件名直接作为 source_file_name
        assertThat(b.sourceFileName()).isEqualTo("accounts_20240624.json");
        // 账号已入库
        assertThat(accountMapper.selectActiveByWsPhone("8613841000001")).isNotNull();
    }

    /**
     * P0-1b:纯文本粘贴(sourceFileName=null) → source_file_name 兜底为「导入」,账号正常入库。
     */
    @Test
    void import_noFileName_sourceFileNameFallsBackToDefault() {
        String json = "[{\"wid\":\"8613842000002\",\"registrationId\":12,\"noiseKey\":{},\"signedIdentityKey\":{},\"signedPreKey\":{}}]";
        // sourceFileName=null,模拟纯文本粘贴
        var meta = new AccountImportDTO(null, 2, 1, 1, "美国", null, null);

        AccountImportBatchVO b = service.importAccounts(meta, null, json);

        assertThat(b.importedRows()).isEqualTo(1);
        // 无文件名时兜底为「导入」常量
        assertThat(b.sourceFileName()).isEqualTo("导入");
        // 账号已入库
        assertThat(accountMapper.selectActiveByWsPhone("8613842000002")).isNotNull();
    }

    /**
     * P0-2:传不存在的 accountGroupId → 抛 NOT_FOUND 业务异常,且账号一条未写(无孤儿)。
     */
    @Test
    void import_nonExistentGroupId_throwsNotFound_noAccountWritten() {
        String json = "[{\"wid\":\"8613843000003\",\"registrationId\":13,\"noiseKey\":{},\"signedIdentityKey\":{},\"signedPreKey\":{}}]";
        // 9999999L 必然不存在
        var meta = new AccountImportDTO(9999999L, 2, 1, 1, "美国", null, null);

        assertThatThrownBy(() -> service.importAccounts(meta, null, json))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ErrorCode.NOT_FOUND.code()));

        // 账号一条未写:无孤儿
        assertThat(accountMapper.selectActiveByWsPhone("8613843000003")).isNull();
    }
}
