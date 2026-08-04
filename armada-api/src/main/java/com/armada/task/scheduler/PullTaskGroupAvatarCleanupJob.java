package com.armada.task.scheduler;

import com.armada.task.service.PullTaskGroupAvatarService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 清理超过保留期且未被有效任务引用的本地群头像。 */
@Component
public class PullTaskGroupAvatarCleanupJob {

    private static final Logger log =
            LoggerFactory.getLogger(PullTaskGroupAvatarCleanupJob.class);

    private final Path storageRoot;
    private final long pendingTtlMillis;
    private final PullTaskGroupAvatarService avatarService;
    private final Clock clock;

    /** 创建群头像清理任务。 */
    @Autowired
    public PullTaskGroupAvatarCleanupJob(
            @Value("${armada.pull-task.avatar.storage-dir:/app/data/pull-task-avatars}")
            String storageDir,
            @Value("${armada.pull-task.avatar.pending-ttl-ms:86400000}")
            long pendingTtlMillis,
            PullTaskGroupAvatarService avatarService) {
        this(storageDir, pendingTtlMillis, avatarService, Clock.systemUTC());
    }

    PullTaskGroupAvatarCleanupJob(
            String storageDir,
            long pendingTtlMillis,
            PullTaskGroupAvatarService avatarService,
            Clock clock) {
        this.storageRoot = Path.of(storageDir).toAbsolutePath().normalize();
        this.pendingTtlMillis = pendingTtlMillis;
        this.avatarService = avatarService;
        this.clock = clock;
    }

    /** 执行一轮过期未绑定头像清理。 */
    @Scheduled(fixedDelayString = "${armada.pull-task.avatar.cleanup-fixed-delay-ms:3600000}")
    public void cleanup() {
        if (pendingTtlMillis < 1
                || Files.isSymbolicLink(storageRoot)
                || !Files.isDirectory(storageRoot, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        long cutoffEpochMillis = clock.millis() - pendingTtlMillis;
        try (Stream<Path> tenantDirectories = Files.list(storageRoot)) {
            tenantDirectories.forEach(path -> cleanTenantDirectory(path, cutoffEpochMillis));
        } catch (IOException exception) {
            log.warn("群头像清理扫描根目录失败 errorType={}",
                    exception.getClass().getSimpleName());
        }
    }

    private void cleanTenantDirectory(Path tenantDirectory, long cutoffEpochMillis) {
        Long tenantId = tenantId(tenantDirectory);
        if (tenantId == null
                || Files.isSymbolicLink(tenantDirectory)
                || !Files.isDirectory(tenantDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> files = Files.list(tenantDirectory)) {
            files.forEach(path -> deleteIfExpired(path, tenantId, cutoffEpochMillis));
        } catch (IOException exception) {
            log.warn("群头像清理扫描租户目录失败 tenantId={} errorType={}",
                    tenantId, exception.getClass().getSimpleName());
        }
    }

    private void deleteIfExpired(
            Path path,
            long tenantId,
            long cutoffEpochMillis) {
        if (Files.isSymbolicLink(path)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        String fileKey = path.getFileName().toString();
        try {
            avatarService.deleteExpiredUnbound(tenantId, fileKey, cutoffEpochMillis);
        } catch (RuntimeException exception) {
            log.warn("群头像过期文件删除失败 tenantId={} avatarFileKey={} errorType={}",
                    tenantId, fileKey, exception.getClass().getSimpleName());
        }
    }

    private Long tenantId(Path path) {
        if (!storageRoot.equals(path.toAbsolutePath().normalize().getParent())) {
            return null;
        }
        try {
            long tenantId = Long.parseLong(path.getFileName().toString());
            return tenantId > 0 ? tenantId : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

}
