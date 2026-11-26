package org.lite.project.cashiersaas.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.configuration.MediaProperties;
import org.lite.project.cashiersaas.dto.response.MediaUploadResponse;
import org.lite.project.cashiersaas.enums.Permission;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;
import org.lite.project.cashiersaas.exception.runtime.OperationConflictException;
import org.lite.project.cashiersaas.repository.FnbCatalogRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class MediaStorageService {
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    private final MerchantAccessGuard guard;
    private final MediaProperties properties;
    private final FnbCatalogRepository catalog;

    public MediaUploadResponse storeImage(TenantPrincipal principal, UUID outletId, MultipartFile file) {
        guard.require(principal, outletId, Permission.CATALOG_EDIT);
        UUID tenantId = principal.getTenantId();
        try {
            byte[] bytes = file.getBytes();
            String extension = extension(bytes);
            Path tenantDir = Path.of(properties.getDir()).resolve(tenantId.toString());
            if (folderSize(tenantDir) + bytes.length > properties.getMaxTenantBytes()) {
                throw new OperationConflictException("Kuota penyimpanan gambar penuh (50 MB).");
            }
            Files.createDirectories(tenantDir);
            String name = UUID.randomUUID() + "." + extension;
            Path temp = Files.createTempFile(tenantDir, "upload-", ".tmp");
            try {
                Files.write(temp, bytes);
                Files.move(temp, tenantDir.resolve(name), StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temp);
            }
            MediaUploadResponse response = new MediaUploadResponse();
            response.setUrl(properties.getPublicBaseUrl() + "/media/" + tenantId + "/" + name);
            return response;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** Deletes files older than the grace period that no product or logo references; returns the deleted count. */
    public int sweepOrphans(Instant now) {
        Path root = Path.of(properties.getDir());
        if (!Files.isDirectory(root)) return 0;
        Instant cutoff = now.minus(properties.getOrphanGrace());
        int deleted = 0;
        try (Stream<Path> folders = Files.list(root)) {
            for (Path folder : folders.toList()) {
                UUID tenantId = tenantFolder(folder);
                if (tenantId != null) deleted += sweepFolder(folder, catalog.mediaUrls(tenantId), cutoff);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return deleted;
    }

    private int sweepFolder(Path folder, java.util.List<String> urls, Instant cutoff) throws IOException {
        Set<String> referenced = new HashSet<>();
        for (String url : urls) referenced.add(url.substring(url.lastIndexOf('/') + 1));
        int deleted = 0;
        try (Stream<Path> files = Files.list(folder)) {
            for (Path file : files.toList()) {
                try {
                    if (Files.isRegularFile(file) && !referenced.contains(file.getFileName().toString())
                            && Files.getLastModifiedTime(file).toInstant().isBefore(cutoff)) {
                        Files.delete(file);
                        deleted++;
                    }
                } catch (IOException exception) {
                    log.warn("Orphan image could not be deleted: error={}", exception.getClass().getSimpleName());
                }
            }
        }
        return deleted;
    }

    private static UUID tenantFolder(Path folder) {
        if (!Files.isDirectory(folder)) return null;
        try {
            return UUID.fromString(folder.getFileName().toString());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static long folderSize(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) return 0;
        try (Stream<Path> files = Files.list(folder)) {
            long total = 0;
            for (Path file : files.toList()) {
                if (Files.isRegularFile(file)) total += Files.size(file);
            }
            return total;
        }
    }

    private static String extension(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "jpg";
        if (startsWith(b, PNG_MAGIC, 0)) return "png";
        if (b.length >= 12 && startsWith(b, "RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0)
                && startsWith(b, "WEBP".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 8)) return "webp";
        throw new InvalidOperationException("File harus berupa gambar JPEG, PNG, atau WebP.");
    }

    private static boolean startsWith(byte[] data, byte[] prefix, int offset) {
        if (data.length < offset + prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (data[offset + i] != prefix[i]) return false;
        return true;
    }
}
