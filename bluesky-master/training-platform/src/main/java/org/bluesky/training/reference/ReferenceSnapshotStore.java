package org.bluesky.training.reference;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.Iterator;
import java.util.UUID;
import java.util.stream.Stream;

/** P03：平台只读快照副本（详细设计 §11.2）。 */
public final class ReferenceSnapshotStore {

    private ReferenceSnapshotStore() {
    }

    public static String sha256Of(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
            return toHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("无法计算文件 checksum: " + file, e);
        }
    }

    public static String sha256OfText(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return toHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256 实现", e);
        }
    }

    /** 递归复制到唯一 staging，完整成功后以目录重命名一次性发布。 */
    public static void copyPublishedSnapshot(Path sourceDir, Path targetDir) throws IOException {
        if (!Files.isDirectory(sourceDir)) {
            throw new IOException("快照源目录不存在: " + sourceDir);
        }
        Path absoluteTarget = targetDir.toAbsolutePath().normalize();
        Path parent = absoluteTarget.getParent();
        if (parent == null) {
            throw new IOException("快照目标缺少父目录: " + targetDir);
        }
        Files.createDirectories(parent);
        if (Files.exists(absoluteTarget)) {
            throw new IOException("快照目标已存在，拒绝覆盖已发布副本: " + absoluteTarget);
        }
        Path staging = parent.resolve(absoluteTarget.getFileName()
                + ".staging-" + UUID.randomUUID());
        Files.createDirectories(staging);
        boolean moved = false;
        try {
            try (Stream<Path> files = Files.walk(sourceDir)) {
                Iterator<Path> iterator = files.sorted().iterator();
                while (iterator.hasNext()) {
                    Path source = iterator.next();
                    Path destination = staging.resolve(sourceDir.relativize(source));
                    if (Files.isDirectory(source)) {
                        Files.createDirectories(destination);
                    } else if (Files.isRegularFile(source)) {
                        Files.createDirectories(destination.getParent());
                        Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES);
                    } else {
                        throw new IOException("快照包含不支持的文件类型: " + source);
                    }
                }
            }
            movePublishedDirectory(staging, absoluteTarget);
            moved = true;
        } finally {
            if (!moved && Files.exists(staging)) {
                deleteRecursively(staging);
            }
        }
    }

    /**
     * Windows can transiently reject an atomic directory rename while the
     * just-created files are being released by the filesystem/indexer.  Keep
     * the publication atomic when supported, but retry the non-atomic rename
     * only while the target is still absent; never replace an existing snapshot.
     */
    private static void movePublishedDirectory(Path staging, Path target) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            if (Files.exists(target)) {
                throw new IOException("快照目标已存在，拒绝覆盖已发布副本: " + target);
            }
            try {
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
                return;
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                last = unsupported;
            } catch (AccessDeniedException transientFailure) {
                last = transientFailure;
            }
            try {
                Files.move(staging, target);
                return;
            } catch (AccessDeniedException transientFailure) {
                last = transientFailure;
            }
            try {
                Thread.sleep(25L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("发布快照时线程被中断", interrupted);
            }
        }
        throw last == null ? new IOException("无法发布快照目录: " + target) : last;
    }

    public static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            Iterator<Path> iterator = walk.sorted(Comparator.reverseOrder()).iterator();
            while (iterator.hasNext()) {
                Files.delete(iterator.next());
            }
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(Character.forDigit((value >> 4) & 0xF, 16));
            builder.append(Character.forDigit(value & 0xF, 16));
        }
        return builder.toString();
    }
}
