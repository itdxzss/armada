package com.armada.account.takeover;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.armada.platform.protocol.model.command.CredentialFormat;
import com.armada.platform.protocol.model.command.ProtocolOnlineCommandRequest;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 预留恢复必须带归属、原子占用且保持普通上线 payload 字节契约。 */
class AccountReservedCreatorReonlineH2Test {

    private AccountTakeoverH2Support h;

    @BeforeEach
    void setUp() throws Exception {
        h = new AccountTakeoverH2Support();
        h.account(1L, 6, 2, 1, null);
        h.reserve(1L);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void matchingReservationClaimsPendingAndPassesRealOutboxIsolation() throws Exception {
        assertThat(h.online.reonlineReservedCreator(1L, 11L, 111L).accepted()).isTrue();
        assertThat(commandCount()).isOne();
        var payload = new ObjectMapper().readTree(h.jdbc.queryForObject(
                "SELECT payload_json FROM protocol_command_outbox", String.class));
        assertThat(payload.get("pullTaskId").longValue()).isEqualTo(11L);
        assertThat(payload.get("groupExecutionId").longValue()).isEqualTo(111L);
        assertThat(payload.get("source").textValue()).isEqualTo("login_replaced_takeover");
        assertThat(stateValue("account_state")).isEqualTo(6L);
        assertThat(stateValue("login_state")).isEqualTo(3L);
        assertThat(stateValue("offline_since")).isEqualTo(1_000L);
        assertThat(h.online.reonlineReservedCreator(1L, 11L, 111L).accepted()).isFalse();
        assertThat(commandCount()).isOne();
    }

    @Test
    void normalReservedCreatorCanRecoverWithoutBecomingTakingOver() {
        h.jdbc.update("UPDATE account_state SET account_state=2 WHERE account_id=1");
        assertThat(h.online.reonlineReservedCreator(1L, 11L, 111L).accepted()).isTrue();
        assertThat(stateValue("account_state")).isEqualTo(2L);
    }

    @Test
    void wrongTaskExecutionOrTenantSkipsWithoutWriting() {
        assertThat(h.online.reonlineReservedCreator(1L, 12L, 111L).accepted()).isFalse();
        assertThat(h.online.reonlineReservedCreator(1L, 11L, 112L).accepted()).isFalse();
        h.jdbc.update("UPDATE account_creator_deletion SET tenant_id=2 WHERE account_id=1");
        assertThat(h.online.reonlineReservedCreator(1L, 11L, 111L).accepted()).isFalse();
        TenantContext.set(2L);
        assertThat(h.online.reonlineReservedCreator(1L, 11L, 111L).accepted()).isFalse();
        assertThat(commandCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3})
    void onlineOrPendingCreatorIsNotClaimedAgain(int login) {
        h.jdbc.update("UPDATE account_state SET login_state=? WHERE account_id=1", login);
        assertSkipped();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3, 4, 5, 7, 8, 9})
    void excludedLifecycleSkipsWithoutChangingIt(int lifecycle) {
        h.jdbc.update("UPDATE account_state SET account_state=? WHERE account_id=1", lifecycle);
        assertSkipped();
        assertThat(stateValue("account_state")).isEqualTo((long) lifecycle);
    }

    @Test
    void desiredOfflineMuteAndBreakerEachPreventReservedRecovery() {
        h.jdbc.update("UPDATE account_state SET desired_login_state=2 WHERE account_id=1");
        assertSkipped();
        h.jdbc.update("UPDATE account_state SET desired_login_state=1,mute_status=1 WHERE account_id=1");
        assertSkipped();
        h.jdbc.update("UPDATE account_state SET mute_status=NULL WHERE account_id=1");
        h.properties.setBreakerMaxKicks(1);
        h.breaker.recordKick(h.accounts.selectActiveById(1L), 1_001L);
        assertSkipped();
    }

    @Test
    void absentOrDeletingReservationSkips() {
        h.jdbc.update("UPDATE account_creator_deletion SET lifecycle='DELETING' WHERE account_id=1");
        assertSkipped();
        h.jdbc.update("DELETE FROM account_creator_deletion WHERE account_id=1");
        assertSkipped();
    }

    @Test
    void disabledAccountSwitchKeepsReservationAndSkips() {
        h.properties.setEnabled(false);
        assertSkipped();
        assertThat(stateValue("login_state")).isEqualTo(2L);
    }

    @Test
    void allocationFailureRollsBackPendingClaimAndRetainsOfflineStart() {
        when(h.ipProxyMock.allocateOnlineEndpoint(any()))
                .thenThrow(new BusinessException(ErrorCode.VALIDATION, "测试代理耗尽"));
        assertThatThrownBy(() -> h.online.reonlineReservedCreator(1L, 11L, 111L))
                .isInstanceOf(BusinessException.class);
        assertThat(stateValue("login_state")).isEqualTo(2L);
        assertThat(stateValue("offline_since")).isEqualTo(1_000L);
        assertThat(commandCount()).isZero();
    }

    @Test
    void ordinaryOnlinePayloadRemainsByteForByteUnchangedWithNullOwnership() {
        h.jdbc.update("DELETE FROM account_creator_deletion WHERE account_id=1");
        var command = new ProtocolOnlineCommandRequest(1L, "account-1", CredentialFormat.BAILEYS_JSON,
                70L, "manual_online", "oa-fixed", null, ProtocolBackend.ANDROID, false, 1, false, 1);
        h.outbox.enqueueOnlineCommands(List.of(command));
        assertThat(h.jdbc.queryForObject("SELECT payload_json FROM protocol_command_outbox", String.class))
                .isEqualTo("{\"accountId\":1,\"protocolAccountId\":\"account-1\",\"credentialFormat\":\"BAILEYS_JSON\","
                        + "\"proxyId\":70,\"source\":\"manual_online\",\"onlineAttemptId\":\"oa-fixed\","
                        + "\"previousOnlineAttemptId\":null,\"protocolBackend\":\"ANDROID\",\"isBusiness\":false,"
                        + "\"declaredAccountType\":1,\"detectAccountType\":false,\"deviceOs\":1}");
    }

    private void assertSkipped() {
        assertThat(h.online.reonlineReservedCreator(1L, 11L, 111L).accepted()).isFalse();
        assertThat(commandCount()).isZero();
    }

    private int commandCount() {
        return h.jdbc.queryForObject("SELECT COUNT(*) FROM protocol_command_outbox", Integer.class);
    }

    private Long stateValue(String column) {
        return h.jdbc.queryForObject("SELECT " + column + " FROM account_state WHERE account_id=1", Long.class);
    }
}
