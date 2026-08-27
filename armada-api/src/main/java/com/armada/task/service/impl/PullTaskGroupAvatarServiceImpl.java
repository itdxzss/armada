package com.armada.task.service.impl;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.security.DataScope;
import com.armada.shared.security.DataScopeAccess;
import com.armada.shared.security.DataScopeMode;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskGroupAvatarFileMapper;
import com.armada.task.mapper.PullTaskStandardGroupSettingMapper;
import com.armada.task.model.entity.PullTaskGroupAvatarFile;
import com.armada.task.model.vo.PullTaskGroupAvatarContent;
import com.armada.task.model.vo.PullTaskGroupAvatarUploadVO;
import com.armada.task.service.PullTaskGroupAvatarService;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

/** 拉群任务群头像本地文件服务实现。 */
@Service
public class PullTaskGroupAvatarServiceImpl implements PullTaskGroupAvatarService {

    private static final int MAX_BYTES = 512000;
    private static final byte[] PNG_SIGNATURE = {
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
    private static final Pattern AVATAR_FILE_KEY_PATTERN =
            Pattern.compile("[0-9a-f]{32}\\.(?:jpg|png)");

    private final Path storageRoot;
    private final PullTaskStandardGroupSettingMapper settingMapper;
    private final PullTaskGroupAvatarFileMapper fileMapper;
    private final Map<AvatarKey, LockReference> fileLocks = new HashMap<>();

    /** 创建本地头像服务。 */
    public PullTaskGroupAvatarServiceImpl(
            @Value("${armada.pull-task.avatar.storage-dir:/app/data/pull-task-avatars}")
            String storageDir,
            PullTaskStandardGroupSettingMapper settingMapper,
            PullTaskGroupAvatarFileMapper fileMapper) {
        this.storageRoot = Path.of(storageDir).toAbsolutePath().normalize();
        this.settingMapper = settingMapper;
        this.fileMapper = fileMapper;
    }

    /** {@inheritDoc} */
    @Override
    public PullTaskGroupAvatarUploadVO upload(long tenantId, MultipartFile file) {
        DataScope scope = requireUserScope(tenantId);
        ImageFormat format = validateAndRead(file);
        byte[] bytes = format.bytes();
        Path tenantDir = tenantDirectory(tenantId);
        String fileKey = UUID.randomUUID().toString().replace("-", "") + format.extension();
        Path target = safeFile(tenantDir, fileKey);
        Path temporary = null;
        try {
            Files.createDirectories(tenantDir);
            if (Files.isSymbolicLink(tenantDir)) {
                throw new IOException("tenant directory is a symbolic link");
            }
            temporary = Files.createTempFile(tenantDir, ".upload-", ".tmp");
            Files.write(temporary, bytes);
            moveIntoPlace(temporary, target);
        } catch (IOException e) {
            deleteQuietly(temporary);
            throw new BusinessException(ErrorCode.CONFLICT, "群头像保存失败，请重试");
        }
        PullTaskGroupAvatarFile metadata = new PullTaskGroupAvatarFile();
        metadata.setFileKey(fileKey);
        metadata.setOwnerUserId(scope.ownerUserIdForCreate());
        metadata.setCreatedAt(System.currentTimeMillis());
        try {
            fileMapper.insert(metadata);
        } catch (RuntimeException exception) {
            deleteQuietly(target);
            throw new BusinessException(ErrorCode.CONFLICT, "群头像保存失败，请重试");
        }
        return new PullTaskGroupAvatarUploadVO(
                fileKey, displayFileName(file), previewUrl(fileKey));
    }

    /** {@inheritDoc} */
    @Override
    public PullTaskGroupAvatarContent content(long tenantId, String fileKey) {
        DataScope scope = requireUserScope(tenantId);
        String canonicalFileKey = requireCanonicalFileKey(fileKey);
        requireMetadataAccess(canonicalFileKey, scope, true);
        return readContent(tenantId, canonicalFileKey);
    }

    /** {@inheritDoc} */
    @Override
    public PullTaskGroupAvatarContent contentForTaskExecution(long tenantId, String fileKey) {
        String canonicalFileKey = requireCanonicalFileKey(fileKey);
        return readContent(tenantId, canonicalFileKey);
    }

    private void requireUnbound(long tenantId, String fileKey) {
        String canonicalFileKey = requireCanonicalFileKey(fileKey);
        requireFile(tenantId, canonicalFileKey);
        if (activeBindingCount(tenantId, canonicalFileKey) > 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "群头像已被任务使用");
        }
    }

    /** {@inheritDoc} */
    @Override
    public void reserveForBinding(long tenantId, String fileKey) {
        DataScope scope = requireUserScope(tenantId);
        String canonicalFileKey = requireCanonicalFileKey(fileKey);
        PullTaskGroupAvatarFile metadata =
                requireMetadataAccess(canonicalFileKey, scope, false);
        DataScopeAccess.requireOwnedByActorForCreate(
                scope, List.of(metadata.getOwnerUserId()), "群头像");
        AvatarKey key = new AvatarKey(tenantId, canonicalFileKey);
        LockReference reference = acquire(key);
        boolean retainedByTransaction = false;
        try {
            requireUnbound(tenantId, canonicalFileKey);
            if (!TransactionSynchronizationManager.isSynchronizationActive()) {
                throw new BusinessException(ErrorCode.CONFLICT, "群头像绑定必须在事务中完成");
            }
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCompletion(int status) {
                            release(key, reference);
                        }
                    });
            retainedByTransaction = true;
        } finally {
            if (!retainedByTransaction) {
                release(key, reference);
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public void delete(long tenantId, String fileKey) {
        DataScope scope = requireUserScope(tenantId);
        String canonicalFileKey = requireCanonicalFileKey(fileKey);
        requireMetadataAccess(canonicalFileKey, scope, true);
        deleteUnboundFile(tenantId, canonicalFileKey);
    }

    /** {@inheritDoc} */
    @Override
    public void deleteAfterTaskRemoval(long tenantId, String fileKey) {
        String canonicalFileKey = requireCanonicalFileKey(fileKey);
        deleteUnboundFile(tenantId, canonicalFileKey);
    }

    private void deleteUnboundFile(long tenantId, String canonicalFileKey) {
        AvatarKey key = new AvatarKey(tenantId, canonicalFileKey);
        LockReference reference = acquire(key);
        try {
            requireUnbound(tenantId, canonicalFileKey);
            Path path = requireFile(tenantId, canonicalFileKey);
            try {
                Files.delete(path);
            } catch (IOException e) {
                throw new BusinessException(ErrorCode.CONFLICT, "群头像删除失败，请重试");
            }
            deleteMetadata(tenantId, canonicalFileKey);
        } finally {
            release(key, reference);
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean deleteExpiredUnbound(
            long tenantId, String fileKey, long cutoffEpochMillis) {
        String canonicalFileKey = requireCanonicalFileKey(fileKey);
        AvatarKey key = new AvatarKey(tenantId, canonicalFileKey);
        LockReference reference = acquire(key);
        try {
            Path path = safeFile(tenantDirectory(tenantId), canonicalFileKey);
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || activeBindingCount(tenantId, canonicalFileKey) > 0) {
                return false;
            }
            try {
                if (Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS)
                        .toMillis() > cutoffEpochMillis) {
                    return false;
                }
                boolean deleted = Files.deleteIfExists(path);
                if (deleted) {
                    deleteMetadata(tenantId, canonicalFileKey);
                }
                return deleted;
            } catch (IOException exception) {
                return false;
            }
        } finally {
            release(key, reference);
        }
    }

    private long activeBindingCount(long tenantId, String fileKey) {
        Long previousTenantId = TenantContext.get();
        try {
            TenantContext.set(tenantId);
            return settingMapper.countActiveTaskBindings(fileKey);
        } finally {
            if (previousTenantId == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previousTenantId);
            }
        }
    }

    private PullTaskGroupAvatarFile requireMetadataAccess(
            String fileKey, DataScope scope, boolean allowHistoricalForAdmin) {
        PullTaskGroupAvatarFile metadata = fileMapper.selectByFileKeyForScope(fileKey, scope);
        if (metadata == null) {
            if (allowHistoricalForAdmin && scope.isAll()) {
                return null;
            }
            throw notFound();
        }
        DataScopeAccess.requireCanAccess(scope, metadata.getOwnerUserId(), "群头像");
        return metadata;
    }

    private void deleteMetadata(long tenantId, String fileKey) {
        Long previousTenantId = TenantContext.get();
        try {
            TenantContext.set(tenantId);
            fileMapper.deleteByFileKey(fileKey);
        } finally {
            restoreTenant(previousTenantId);
        }
    }

    private PullTaskGroupAvatarContent readContent(long tenantId, String fileKey) {
        Path path = requireFile(tenantId, fileKey);
        try {
            return new PullTaskGroupAvatarContent(contentType(fileKey), Files.readAllBytes(path));
        } catch (IOException e) {
            throw notFound();
        }
    }

    private static DataScope requireUserScope(long tenantId) {
        Long currentTenantId = TenantContext.get();
        if (currentTenantId == null || currentTenantId != tenantId) {
            throw new BusinessException(ErrorCode.TENANT_MISSING);
        }
        DataScope scope = DataScopeAccess.requireCurrent();
        if (scope.mode() == DataScopeMode.SYSTEM) {
            throw new BusinessException(
                    ErrorCode.ACCESS_DENIED, "后台范围不能直接访问用户私有数据");
        }
        return scope;
    }

    private static void restoreTenant(Long previousTenantId) {
        if (previousTenantId == null) {
            TenantContext.clear();
        } else {
            TenantContext.set(previousTenantId);
        }
    }

    private LockReference acquire(AvatarKey key) {
        LockReference reference;
        synchronized (fileLocks) {
            reference = fileLocks.computeIfAbsent(key, ignored -> new LockReference());
            reference.users += 1;
        }
        reference.lock.lock();
        return reference;
    }

    private void release(AvatarKey key, LockReference reference) {
        reference.lock.unlock();
        synchronized (fileLocks) {
            reference.users -= 1;
            if (reference.users == 0) {
                fileLocks.remove(key, reference);
            }
        }
    }

    private ImageFormat validateAndRead(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "请选择群头像");
        }
        String name = displayFileName(file).toLowerCase(Locale.ROOT);
        String type = file.getContentType() == null
                ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        boolean jpeg = name.endsWith(".jpg") || name.endsWith(".jpeg");
        boolean png = name.endsWith(".png");
        if ((!jpeg && !png)
                || (jpeg && !"image/jpeg".equals(type))
                || (png && !"image/png".equals(type))) {
            throw new BusinessException(ErrorCode.VALIDATION, "群头像只支持 JPG、JPEG、PNG");
        }
        byte[] bytes = readBytes(file);
        if (bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw new BusinessException(ErrorCode.VALIDATION, "群头像大小须在 500KB 以内");
        }
        if (jpeg && !isJpeg(bytes) || png && !isPng(bytes)) {
            throw new BusinessException(ErrorCode.VALIDATION, "群头像文件内容与格式不符");
        }
        return new ImageFormat(jpeg ? ".jpg" : ".png", bytes);
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.VALIDATION, "群头像读取失败");
        }
    }

    private Path requireFile(long tenantId, String fileKey) {
        Path path = safeFile(tenantDirectory(tenantId), fileKey);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw notFound();
        }
        return path;
    }

    private Path tenantDirectory(long tenantId) {
        if (tenantId <= 0) {
            throw notFound();
        }
        Path tenantDir = storageRoot.resolve(Long.toString(tenantId)).normalize();
        if (!tenantDir.getParent().equals(storageRoot)) {
            throw notFound();
        }
        return tenantDir;
    }

    private static Path safeFile(Path tenantDir, String fileKey) {
        String canonicalFileKey = requireCanonicalFileKey(fileKey);
        Path target = tenantDir.resolve(canonicalFileKey).normalize();
        if (!tenantDir.equals(target.getParent())) {
            throw notFound();
        }
        return target;
    }

    private static String requireCanonicalFileKey(String fileKey) {
        if (fileKey == null || !AVATAR_FILE_KEY_PATTERN.matcher(fileKey).matches()) {
            throw notFound();
        }
        return fileKey;
    }

    private static void moveIntoPlace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target);
        }
    }

    private static boolean isJpeg(byte[] bytes) {
        return bytes.length >= 3
                && bytes[0] == (byte) 0xff
                && bytes[1] == (byte) 0xd8
                && bytes[2] == (byte) 0xff;
    }

    private static boolean isPng(byte[] bytes) {
        if (bytes.length < PNG_SIGNATURE.length) {
            return false;
        }
        for (int index = 0; index < PNG_SIGNATURE.length; index++) {
            if (bytes[index] != PNG_SIGNATURE[index]) {
                return false;
            }
        }
        return true;
    }

    private static String displayFileName(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank()) {
            return "avatar";
        }
        String normalized = name.replace('\\', '/');
        int separator = normalized.lastIndexOf('/');
        return normalized.substring(separator + 1).trim();
    }

    private static String contentType(String fileKey) {
        return fileKey.toLowerCase(Locale.ROOT).endsWith(".png")
                ? "image/png" : "image/jpeg";
    }

    private static String previewUrl(String fileKey) {
        return "/api/pull-tasks/standard/group-avatars/" + fileKey;
    }

    private static BusinessException notFound() {
        return new BusinessException(ErrorCode.NOT_FOUND, "群头像不存在");
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 上传失败时尽力清理临时文件，原始业务错误优先返回。
        }
    }

    private record ImageFormat(String extension, byte[] bytes) {
    }

    private record AvatarKey(long tenantId, String fileKey) {
    }

    private static final class LockReference {
        private final ReentrantLock lock = new ReentrantLock();
        private int users;
    }
}
