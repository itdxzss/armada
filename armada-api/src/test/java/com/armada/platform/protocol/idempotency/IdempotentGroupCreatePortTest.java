package com.armada.platform.protocol.idempotency;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.GroupCreateCommand;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupCreateParticipantResult;
import com.armada.platform.protocol.model.result.GroupCreateResult;
import com.armada.platform.protocol.port.GroupCreatePort;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotentGroupCreatePortTest {

    private static final String OPERATION_ID = "group-pull-execution:19";

    @Test
    void storesAndReplaysTheFirstSuccessfulResult() {
        InMemoryStore store = new InMemoryStore();
        AtomicInteger delegateCalls = new AtomicInteger();
        GroupCreateResult expected = new GroupCreateResult(
                "120363created@g.us",
                true,
                List.of(new GroupCreateParticipantResult(
                        "919000000002@s.whatsapp.net", "OK", "200")));
        GroupCreatePort port = new IdempotentGroupCreatePort(command -> {
            delegateCalls.incrementAndGet();
            assertThat(store.find(OPERATION_ID))
                    .get()
                    .extracting(GroupCreateIdempotencyRecord::status)
                    .isEqualTo(GroupCreateIdempotencyRecord.Status.PROCESSING);
            return expected;
        }, store);

        assertThat(port.create(command())).isEqualTo(expected);
        assertThat(port.create(command())).isEqualTo(expected);
        assertThat(delegateCalls).hasValue(1);
        assertThat(store.find(OPERATION_ID))
                .get()
                .extracting(GroupCreateIdempotencyRecord::result)
                .isEqualTo(expected);
    }

    @Test
    void rejectsAnOperationAlreadyBeingProcessed() {
        InMemoryStore store = new InMemoryStore();
        store.records.put(OPERATION_ID, GroupCreateIdempotencyRecord.processing("another-claim"));
        AtomicInteger delegateCalls = new AtomicInteger();
        GroupCreatePort port = new IdempotentGroupCreatePort(command -> {
            delegateCalls.incrementAndGet();
            return success();
        }, store);

        assertThatThrownBy(() -> port.create(command()))
                .isInstanceOfSatisfying(ProtocolException.class, exception ->
                        assertThat(exception.errorCode())
                                .isEqualTo(ProtocolErrorCode.GROUP_CREATE_RESULT_UNCONFIRMED));
        assertThat(delegateCalls).hasValue(0);
    }

    @Test
    void doesNotCallDelegateWhenTheStoreIsUnavailable() {
        GroupCreateIdempotencyStore store = new FailingStore();
        AtomicInteger delegateCalls = new AtomicInteger();
        GroupCreatePort port = new IdempotentGroupCreatePort(command -> {
            delegateCalls.incrementAndGet();
            return success();
        }, store);

        assertThatThrownBy(() -> port.create(command()))
                .isInstanceOfSatisfying(ProtocolException.class, exception ->
                        assertThat(exception.errorCode())
                                .isEqualTo(ProtocolErrorCode.IDEMPOTENCY_STORE_UNAVAILABLE));
        assertThat(delegateCalls).hasValue(0);
    }

    @Test
    void clearsProcessingOnlyWhenTheDelegateClearlyDidNotCreateAGroup() {
        InMemoryStore store = new InMemoryStore();
        AtomicInteger delegateCalls = new AtomicInteger();
        GroupCreatePort port = new IdempotentGroupCreatePort(command -> {
            if (delegateCalls.incrementAndGet() == 1) {
                throw new ProtocolException(ProtocolErrorCode.BAD_REQUEST, "明确拒绝");
            }
            return success();
        }, store);

        assertThatThrownBy(() -> port.create(command()))
                .isInstanceOfSatisfying(ProtocolException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ProtocolErrorCode.BAD_REQUEST));
        assertThat(store.find(OPERATION_ID)).isEmpty();
        assertThat(port.create(command())).isEqualTo(success());
        assertThat(delegateCalls).hasValue(2);
    }

    @Test
    void preservesProcessingWhenTheCreateResultCannotBeConfirmed() {
        InMemoryStore store = new InMemoryStore();
        AtomicInteger delegateCalls = new AtomicInteger();
        GroupCreatePort port = new IdempotentGroupCreatePort(command -> {
            delegateCalls.incrementAndGet();
            throw new ProtocolException(ProtocolErrorCode.TIMEOUT, "超时");
        }, store);

        assertThatThrownBy(() -> port.create(command()))
                .isInstanceOfSatisfying(ProtocolException.class, exception ->
                        assertThat(exception.errorCode())
                                .isEqualTo(ProtocolErrorCode.GROUP_CREATE_RESULT_UNCONFIRMED));
        assertThatThrownBy(() -> port.create(command()))
                .isInstanceOfSatisfying(ProtocolException.class, exception ->
                        assertThat(exception.errorCode())
                                .isEqualTo(ProtocolErrorCode.GROUP_CREATE_RESULT_UNCONFIRMED));
        assertThat(delegateCalls).hasValue(1);
    }

    private static GroupCreateCommand command() {
        return new GroupCreateCommand(
                new ProtocolAccountRef(
                        7L, ProtocolBackend.ANDROID, "android-7", "919000000001"),
                "活动群-1",
                List.of("919000000002"),
                false,
                OPERATION_ID);
    }

    private static GroupCreateResult success() {
        return new GroupCreateResult("120363created@g.us", false, List.of());
    }

    private static final class InMemoryStore implements GroupCreateIdempotencyStore {
        private final Map<String, GroupCreateIdempotencyRecord> records = new HashMap<>();

        @Override
        public Optional<GroupCreateIdempotencyRecord> find(String operationId) {
            return Optional.ofNullable(records.get(operationId));
        }

        @Override
        public boolean tryBegin(String operationId, String claimToken) {
            return records.putIfAbsent(
                    operationId, GroupCreateIdempotencyRecord.processing(claimToken)) == null;
        }

        @Override
        public void saveSuccess(
                String operationId,
                String claimToken,
                GroupCreateResult result) {
            records.compute(operationId, (key, record) -> {
                if (record == null || !claimToken.equals(record.claimToken())) {
                    throw new IllegalStateException("claimToken 不匹配");
                }
                return GroupCreateIdempotencyRecord.succeeded(claimToken, result);
            });
        }

        @Override
        public void clearProcessing(String operationId, String claimToken) {
            records.computeIfPresent(operationId, (key, record) ->
                    claimToken.equals(record.claimToken()) ? null : record);
        }
    }

    private static final class FailingStore implements GroupCreateIdempotencyStore {
        @Override
        public Optional<GroupCreateIdempotencyRecord> find(String operationId) {
            throw new IllegalStateException("Redis 不可用");
        }

        @Override
        public boolean tryBegin(String operationId, String claimToken) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void saveSuccess(
                String operationId,
                String claimToken,
                GroupCreateResult result) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void clearProcessing(String operationId, String claimToken) {
            throw new UnsupportedOperationException();
        }
    }
}
