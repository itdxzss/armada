package com.armada.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.armada.shared.exception.BusinessException;
import com.armada.task.mapper.PullTaskStandardGroupSettingMapper;
import com.armada.task.model.vo.PullTaskGroupAvatarUploadVO;
import com.armada.task.service.impl.PullTaskGroupAvatarServiceImpl;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 拉群任务头像本地存储安全规则测试。 */
class PullTaskGroupAvatarServiceTest {

    private static final byte[] JPEG = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x01};
    private static final byte[] PNG = {
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x01};

    @TempDir
    private Path storageRoot;

    private PullTaskStandardGroupSettingMapper mapper;
    private PullTaskGroupAvatarService service;

    @BeforeEach
    void setUp() {
        mapper = mock(PullTaskStandardGroupSettingMapper.class);
        service = new PullTaskGroupAvatarServiceImpl(storageRoot.toString(), mapper);
    }

    @Test
    void acceptsJpgJpegAndPngAndGeneratesSafeCanonicalKeys() throws Exception {
        PullTaskGroupAvatarUploadVO jpg = service.upload(7L, file("头像.jpg", "image/jpeg", JPEG));
        PullTaskGroupAvatarUploadVO jpeg = service.upload(7L, file("头像.JPEG", "image/jpeg", JPEG));
        PullTaskGroupAvatarUploadVO png = service.upload(7L, file("头像.PNG", "image/png", PNG));

        assertSafeKey(jpg.avatarFileKey(), ".jpg");
        assertSafeKey(jpeg.avatarFileKey(), ".jpg");
        assertSafeKey(png.avatarFileKey(), ".png");
        assertThat(Files.size(storageRoot.resolve("7").resolve(png.avatarFileKey())))
                .isEqualTo(PNG.length);
        assertThat(png.previewUrl()).endsWith("/" + png.avatarFileKey());
    }

    @Test
    void rejectsEmptyAndActualContentOverFiveHundredKilobytes() {
        assertThatThrownBy(() -> service.upload(
                7L, file("empty.jpg", "image/jpeg", new byte[0])))
                .isInstanceOf(BusinessException.class);
        byte[] oversized = new byte[512001];
        oversized[0] = (byte) 0xff;
        oversized[1] = (byte) 0xd8;
        oversized[2] = (byte) 0xff;
        assertThatThrownBy(() -> service.upload(
                7L, file("big.jpg", "image/jpeg", oversized)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("500KB");
    }

    @Test
    void rejectsUnsupportedExtensionMimeAndForgedSignature() {
        assertThatThrownBy(() -> service.upload(7L, file("a.gif", "image/gif", JPEG)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.upload(7L, file("a.jpg", "image/png", JPEG)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.upload(
                7L, file("a.png", "image/png", new byte[] {1, 2, 3, 4, 5, 6, 7, 8})))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void tenantCannotReadOrDeleteAnotherTenantsKey() {
        PullTaskGroupAvatarUploadVO uploaded =
                service.upload(8L, file("a.png", "image/png", PNG));

        assertThatThrownBy(() -> service.content(7L, uploaded.avatarFileKey()))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.delete(7L, uploaded.avatarFileKey()))
                .isInstanceOf(BusinessException.class);
        assertThat(service.content(8L, uploaded.avatarFileKey()).content()).isEqualTo(PNG);
    }

    @Test
    void rejectsPathAliasesForAnExistingAvatar() {
        PullTaskGroupAvatarUploadVO uploaded =
                service.upload(7L, file("a.png", "image/png", PNG));
        String alias = "sub/../" + uploaded.avatarFileKey();

        assertThatThrownBy(() -> service.content(7L, alias))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在");
        assertThatThrownBy(() -> service.requireUnbound(7L, alias))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在");
        assertThatThrownBy(() -> service.delete(7L, alias))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在");

        TransactionSynchronizationManager.initSynchronization();
        try {
            assertThatThrownBy(() -> service.reserveForBinding(7L, alias))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("不存在");
            assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        verifyNoInteractions(mapper);
        assertThat(storageRoot.resolve("7").resolve(uploaded.avatarFileKey())).exists();
    }

    @Test
    void deleteIsAllowedOnlyWhileFileIsUnbound() throws Exception {
        PullTaskGroupAvatarUploadVO uploaded =
                service.upload(7L, file("a.jpg", "image/jpeg", JPEG));
        when(mapper.countActiveTaskBindings(uploaded.avatarFileKey())).thenReturn(1L);

        assertThatThrownBy(() -> service.delete(7L, uploaded.avatarFileKey()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("使用");

        when(mapper.countActiveTaskBindings(uploaded.avatarFileKey())).thenReturn(0L);
        service.delete(7L, uploaded.avatarFileKey());
        assertThat(Files.exists(storageRoot.resolve("7").resolve(uploaded.avatarFileKey())))
                .isFalse();
    }

    @Test
    void bindingReservationPreventsConcurrentDeleteUntilTransactionCompletes() throws Exception {
        PullTaskGroupAvatarUploadVO uploaded =
                service.upload(7L, file("a.jpg", "image/jpeg", JPEG));
        AtomicLong activeBindings = new AtomicLong();
        when(mapper.countActiveTaskBindings(uploaded.avatarFileKey()))
                .thenAnswer(ignored -> activeBindings.get());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        TransactionSynchronizationManager.initSynchronization();
        List<TransactionSynchronization> synchronizations = List.of();
        try {
            service.reserveForBinding(7L, uploaded.avatarFileKey());
            synchronizations = TransactionSynchronizationManager.getSynchronizations();
            CountDownLatch deletionStarted = new CountDownLatch(1);
            Future<?> deletion = executor.submit(() -> {
                deletionStarted.countDown();
                service.delete(7L, uploaded.avatarFileKey());
            });

            assertThat(deletionStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> deletion.get(100, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            activeBindings.set(1L);
            synchronizations.forEach(callback -> callback.afterCompletion(
                    TransactionSynchronization.STATUS_COMMITTED));
            synchronizations = List.of();

            assertThatThrownBy(() -> deletion.get(1, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(BusinessException.class);
            assertThat(storageRoot.resolve("7").resolve(uploaded.avatarFileKey())).exists();
        } finally {
            synchronizations.forEach(callback -> callback.afterCompletion(
                    TransactionSynchronization.STATUS_ROLLED_BACK));
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.clearSynchronization();
            }
            executor.shutdownNow();
        }
    }

    private static MockMultipartFile file(String name, String type, byte[] bytes) {
        return new MockMultipartFile("file", name, type, bytes);
    }

    private static void assertSafeKey(String key, String suffix) {
        assertThat(key).endsWith(suffix)
                .doesNotContain("/")
                .doesNotContain("\\")
                .doesNotContain("..");
    }
}
