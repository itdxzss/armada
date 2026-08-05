package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.armada.task.mapper.PullTaskStandardGroupSettingMapper;
import com.armada.task.service.PullTaskGroupAvatarService;
import com.armada.task.service.impl.PullTaskGroupAvatarServiceImpl;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.context.support.TestPropertySourceUtils;

/** 待绑定群头像过期清理测试。 */
class PullTaskGroupAvatarCleanupJobTest {

    private static final Instant NOW = Instant.parse("2026-08-04T12:00:00Z");
    private static final long ONE_DAY_MILLIS = 86_400_000L;

    @TempDir
    Path sandbox;

    private final PullTaskStandardGroupSettingMapper mapper =
            mock(PullTaskStandardGroupSettingMapper.class);

    @Test
    void springInstantiatesTheConfiguredProductionConstructor() {
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext()) {
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
                    context,
                    "armada.pull-task.avatar.storage-dir=" + storageRoot(),
                    "armada.pull-task.avatar.pending-ttl-ms=" + ONE_DAY_MILLIS);
            context.registerBean(
                    PullTaskGroupAvatarService.class,
                    () -> mock(PullTaskGroupAvatarService.class));
            context.register(PullTaskGroupAvatarCleanupJob.class);

            context.refresh();

            assertThat(context.getBean(PullTaskGroupAvatarCleanupJob.class)).isNotNull();
        }
    }

    @Test
    void keepsYoungAndActiveFilesButDeletesOldUnboundAndSoftDeletedFiles() throws Exception {
        Path tenantDir = Files.createDirectories(storageRoot().resolve("3"));
        String youngKey = "11111111111111111111111111111111.png";
        String activeKey = "22222222222222222222222222222222.png";
        String unboundKey = "33333333333333333333333333333333.png";
        String softDeletedKey = "44444444444444444444444444444444.png";
        Path young = write(tenantDir, youngKey, NOW.minusSeconds(60));
        Path active = write(tenantDir, activeKey, NOW.minusSeconds(90_000));
        Path unbound = write(tenantDir, unboundKey, NOW.minusSeconds(90_000));
        Path softDeleted = write(tenantDir, softDeletedKey, NOW.minusSeconds(90_000));
        Path legacy = write(tenantDir, "legacy.png", NOW.minusSeconds(90_000));
        when(mapper.countActiveTaskBindings(activeKey)).thenReturn(1L);

        cleanupJob().cleanup();

        assertThat(young).exists();
        assertThat(active).exists();
        assertThat(unbound).doesNotExist();
        assertThat(softDeleted).doesNotExist();
        assertThat(legacy).exists();
    }

    @Test
    void neverFollowsSymbolicLinksOutsideStorageRoot() throws Exception {
        Path outsideDir = Files.createDirectories(sandbox.resolve("outside"));
        Path outsideFile = write(outsideDir, "outside.png", NOW.minusSeconds(90_000));
        Path tenantDir = Files.createDirectories(storageRoot().resolve("3"));
        Path fileLink = tenantDir.resolve("linked.png");
        Path tenantLink = storageRoot().resolve("4");
        try {
            Files.createSymbolicLink(fileLink, outsideFile);
            Files.createSymbolicLink(tenantLink, outsideDir);
        } catch (FileSystemException | UnsupportedOperationException exception) {
            assumeTrue(false,
                    "当前文件系统或进程权限不支持创建符号链接: " + exception.getMessage());
        }
        cleanupJob().cleanup();

        assertThat(fileLink).exists();
        assertThat(tenantLink).exists();
        assertThat(outsideFile).exists();
    }

    private PullTaskGroupAvatarCleanupJob cleanupJob() {
        return new PullTaskGroupAvatarCleanupJob(
                storageRoot().toString(), ONE_DAY_MILLIS,
                new PullTaskGroupAvatarServiceImpl(storageRoot().toString(), mapper),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Path storageRoot() {
        return sandbox.resolve("storage");
    }

    private static Path write(Path directory, String key, Instant modifiedAt) throws Exception {
        Path path = Files.write(directory.resolve(key), new byte[] {1});
        Files.setLastModifiedTime(path, FileTime.from(modifiedAt));
        return path;
    }
}
