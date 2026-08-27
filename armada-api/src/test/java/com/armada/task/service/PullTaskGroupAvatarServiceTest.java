package com.armada.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.security.DataScope;
import com.armada.shared.security.DataScopeContext;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskGroupAvatarFileMapper;
import com.armada.task.mapper.PullTaskStandardGroupSettingMapper;
import com.armada.task.model.entity.PullTaskGroupAvatarFile;
import com.armada.task.model.vo.PullTaskGroupAvatarUploadVO;
import com.armada.task.service.impl.PullTaskGroupAvatarServiceImpl;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
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
    private PullTaskGroupAvatarFileMapper fileMapper;
    private PullTaskGroupAvatarService service;
    private Map<String, PullTaskGroupAvatarFile> metadata;

    @BeforeEach
    void setUp() {
        TenantContext.set(7L);
        DataScopeContext.open(DataScope.self(501L));
        mapper = mock(PullTaskStandardGroupSettingMapper.class);
        fileMapper = mock(PullTaskGroupAvatarFileMapper.class);
        metadata = new HashMap<>();
        doAnswer(invocation -> {
            PullTaskGroupAvatarFile row = invocation.getArgument(0);
            metadata.put(row.getFileKey(), row);
            return 1;
        }).when(fileMapper).insert(any(PullTaskGroupAvatarFile.class));
        when(fileMapper.selectByFileKeyForScope(any(), any()))
                .thenAnswer(invocation -> metadata.get(invocation.getArgument(0)));
        when(fileMapper.deleteByFileKey(any()))
                .thenAnswer(invocation -> metadata.remove(invocation.getArgument(0)) == null ? 0 : 1);
        service = new PullTaskGroupAvatarServiceImpl(
                storageRoot.toString(), mapper, fileMapper);
    }

    @AfterEach
    void tearDown() {
        DataScopeContext.clear();
        TenantContext.clear();
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
        assertThat(metadata.values())
                .extracting(PullTaskGroupAvatarFile::getOwnerUserId)
                .containsOnly(501L);
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
        PullTaskGroupAvatarUploadVO uploaded;
        TenantContext.set(8L);
        try (var ignored = DataScopeContext.open(DataScope.self(502L))) {
            uploaded = service.upload(8L, file("a.png", "image/png", PNG));
        }
        TenantContext.set(7L);

        assertThatThrownBy(() -> service.content(7L, uploaded.avatarFileKey()))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.delete(7L, uploaded.avatarFileKey()))
                .isInstanceOf(BusinessException.class);
        TenantContext.set(8L);
        try (var ignored = DataScopeContext.open(DataScope.self(502L))) {
            assertThat(service.content(8L, uploaded.avatarFileKey()).content()).isEqualTo(PNG);
        }
    }

    @Test
    void ordinaryUserCannotReadDeleteOrBindAnotherUsersAvatarButAdminCanPreview() {
        PullTaskGroupAvatarUploadVO uploaded;
        try (var ignored = DataScopeContext.open(DataScope.self(502L))) {
            uploaded = service.upload(7L, file("u2.png", "image/png", PNG));
        }

        assertThatThrownBy(() -> service.content(7L, uploaded.avatarFileKey()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在");
        assertThatThrownBy(() -> service.delete(7L, uploaded.avatarFileKey()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在");

        try (var ignored = DataScopeContext.open(DataScope.all(9001L))) {
            assertThat(service.content(7L, uploaded.avatarFileKey()).content()).isEqualTo(PNG);
            TransactionSynchronizationManager.initSynchronization();
            try {
                assertThatThrownBy(() -> service.reserveForBinding(
                        7L, uploaded.avatarFileKey()))
                        .isInstanceOf(BusinessException.class)
                        .hasMessageContaining("当前操作者自己的资源");
            } finally {
                TransactionSynchronizationManager.clearSynchronization();
            }
        }
    }

    @Test
    void userMethodsFailClosedWithoutUserScopeWhileInternalExecutionCanRead() {
        PullTaskGroupAvatarUploadVO uploaded =
                service.upload(7L, file("a.png", "image/png", PNG));

        DataScopeContext.clear();
        assertThatThrownBy(() -> service.content(7L, uploaded.avatarFileKey()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("数据访问范围");
        try (var ignored = DataScopeContext.open(DataScope.system("avatar protocol execution"))) {
            assertThatThrownBy(() -> service.content(7L, uploaded.avatarFileKey()))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("后台范围");
            assertThat(service.contentForTaskExecution(7L, uploaded.avatarFileKey()).content())
                    .isEqualTo(PNG);
        }
    }

    @Test
    void historicalFileWithoutMetadataIsAdminOnly() throws Exception {
        String key = "99999999999999999999999999999999.png";
        Path tenantDir = Files.createDirectories(storageRoot.resolve("7"));
        Files.write(tenantDir.resolve(key), PNG);

        assertThatThrownBy(() -> service.content(7L, key))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在");
        try (var ignored = DataScopeContext.open(DataScope.all(9001L))) {
            assertThat(service.content(7L, key).content()).isEqualTo(PNG);
            service.delete(7L, key);
        }
        assertThat(tenantDir.resolve(key)).doesNotExist();
    }

    @Test
    void metadataInsertFailureRemovesNewPhysicalFile() throws Exception {
        doThrow(new IllegalStateException("db unavailable"))
                .when(fileMapper).insert(any(PullTaskGroupAvatarFile.class));

        assertThatThrownBy(() -> service.upload(
                7L, file("a.png", "image/png", PNG)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("保存失败");
        Path tenantDir = storageRoot.resolve("7");
        assertThat(tenantDir).isDirectory();
        try (var files = Files.list(tenantDir)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void rejectsPathAliasesForAnExistingAvatar() {
        PullTaskGroupAvatarUploadVO uploaded =
                service.upload(7L, file("a.png", "image/png", PNG));
        String alias = "sub/../" + uploaded.avatarFileKey();

        assertThatThrownBy(() -> service.content(7L, alias))
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
                TenantContext.set(7L);
                try (var ignored = DataScopeContext.open(DataScope.self(501L))) {
                    deletionStarted.countDown();
                    service.delete(7L, uploaded.avatarFileKey());
                } finally {
                    TenantContext.clear();
                }
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
