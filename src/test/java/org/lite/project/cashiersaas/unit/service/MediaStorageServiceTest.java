package org.lite.project.cashiersaas.unit.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.lite.project.cashiersaas.component.MerchantAccessContext;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.configuration.MediaProperties;
import org.lite.project.cashiersaas.dto.response.MediaUploadResponse;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.enums.Permission;
import org.lite.project.cashiersaas.enums.Role;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;
import org.lite.project.cashiersaas.exception.runtime.OperationConflictException;
import org.lite.project.cashiersaas.exception.runtime.ResourceNotFoundException;
import org.lite.project.cashiersaas.repository.FnbCatalogRepository;
import org.lite.project.cashiersaas.service.MediaStorageService;
import org.lite.project.cashiersaas.service.MerchantAccessGuard;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class MediaStorageServiceTest {
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3, 4};
    private static final byte[] WEBP = {'R', 'I', 'F', 'F', 9, 9, 9, 9, 'W', 'E', 'B', 'P', 1, 2};
    private static final String BASE = "http://localhost:8080";

    @Mock private MerchantAccessGuard guard;
    @Mock private FnbCatalogRepository catalog;
    @TempDir private Path dir;

    private final MediaProperties properties = new MediaProperties();
    private final UUID tenantId = UUID.randomUUID();
    private final UUID outletId = UUID.randomUUID();
    private final TenantPrincipal principal = new TenantPrincipal(UUID.randomUUID(), tenantId, outletId,
            UUID.randomUUID(), UUID.randomUUID(), Set.of(Role.CASHIER));
    private MediaStorageService service;

    @BeforeEach
    void setUp() {
        properties.setDir(dir.toString());
        properties.setPublicBaseUrl(BASE);
        properties.setMaxTenantBytes(1000);
        properties.setOrphanGrace(Duration.ofHours(24));
        TenantEntity tenant = new TenantEntity();
        ReflectionTestUtils.setField(tenant, "id", tenantId);
        // The tenant may come from the principal or from the guarded context; both carry the same id.
        lenient().when(guard.require(principal, outletId, Permission.CATALOG_EDIT))
                .thenReturn(new MerchantAccessContext(tenant, null, null));
        service = new MediaStorageService(guard, properties, catalog);
    }

    @Test
    void storesPngUnderTenantFolder() throws IOException {
        MediaUploadResponse response = service.storeImage(principal, outletId,
                new MockMultipartFile("file", "photo.png", "image/png", PNG));

        String name = response.getUrl().substring(response.getUrl().lastIndexOf('/') + 1);
        assertTrue(name.endsWith(".png"));
        UUID.fromString(name.substring(0, name.length() - 4));
        assertEquals(BASE + "/media/" + tenantId + "/" + name, response.getUrl());
        assertArrayEquals(PNG, Files.readAllBytes(dir.resolve(tenantId.toString()).resolve(name)));
        assertEquals(1, fileCount(dir.resolve(tenantId.toString())), "no temp file may be left behind");
    }

    @ParameterizedTest
    @CsvSource({"jpg,x.png", "webp,x.jpg", "png,x.gif"})
    void extensionComesFromContent(String extension, String clientName) throws IOException {
        byte[] bytes = switch (extension) {
            case "jpg" -> JPEG;
            case "webp" -> WEBP;
            default -> PNG;
        };

        MediaUploadResponse response = service.storeImage(principal, outletId,
                new MockMultipartFile("file", clientName, "application/octet-stream", bytes));

        assertTrue(response.getUrl().endsWith("." + extension), response.getUrl());
    }

    @Test
    void rejectsNonImage() throws IOException {
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes(StandardCharsets.UTF_8);
        byte[] text = "not an image".getBytes(StandardCharsets.UTF_8);
        byte[] riffNotWebp = {'R', 'I', 'F', 'F', 1, 2, 3, 4, 'W', 'A', 'V', 'E'};

        for (MockMultipartFile file : List.of(
                new MockMultipartFile("file", "a.png", "image/png", text),
                new MockMultipartFile("file", "a.svg", "image/svg+xml", svg),
                new MockMultipartFile("file", "a.wav", "audio/wav", riffNotWebp),
                new MockMultipartFile("file", "empty.png", "image/png", new byte[0]))) {
            assertThrows(InvalidOperationException.class, () -> service.storeImage(principal, outletId, file));
        }

        assertEquals(0, fileCount(dir.resolve(tenantId.toString())));
    }

    @Test
    void rejectsOverQuota() throws IOException {
        Path tenantDir = Files.createDirectories(dir.resolve(tenantId.toString()));
        Files.write(tenantDir.resolve(UUID.randomUUID() + ".png"), new byte[8]);
        properties.setMaxTenantBytes(10);

        assertThrows(OperationConflictException.class, () -> service.storeImage(principal, outletId,
                new MockMultipartFile("file", "a.png", "image/png", PNG)));

        assertEquals(1, fileCount(tenantDir));
    }

    @Test
    void guardBeforeWrite() throws IOException {
        when(guard.require(principal, outletId, Permission.CATALOG_EDIT)).thenThrow(new ResourceNotFoundException());

        assertThrows(ResourceNotFoundException.class, () -> service.storeImage(principal, outletId,
                new MockMultipartFile("file", "a.png", "image/png", PNG)));

        verify(guard).require(principal, outletId, Permission.CATALOG_EDIT);
        assertFalse(Files.exists(dir.resolve(tenantId.toString())) && fileCount(dir.resolve(tenantId.toString())) > 0);
    }

    @Test
    void sweepDeletesOnlyOldUnreferenced() throws IOException {
        Instant now = Instant.now();
        Path tenantDir = Files.createDirectories(dir.resolve(tenantId.toString()));
        Path oldOrphan = aged(tenantDir, UUID.randomUUID() + ".png", now.minus(Duration.ofHours(48)));
        Path oldProduct = aged(tenantDir, UUID.randomUUID() + ".jpg", now.minus(Duration.ofHours(48)));
        Path oldLogo = aged(tenantDir, UUID.randomUUID() + ".webp", now.minus(Duration.ofHours(48)));
        Path young = aged(tenantDir, UUID.randomUUID() + ".png", now.minus(Duration.ofHours(1)));
        Path otherFolder = Files.createDirectories(dir.resolve("not-a-uuid"));
        Path foreign = aged(otherFolder, "old.png", now.minus(Duration.ofHours(48)));
        when(catalog.mediaUrls(tenantId)).thenReturn(List.of(
                BASE + "/media/" + tenantId + "/" + oldProduct.getFileName(),
                BASE + "/media/" + tenantId + "/" + oldLogo.getFileName()));

        int deleted = service.sweepOrphans(now);

        assertEquals(1, deleted);
        assertFalse(Files.exists(oldOrphan));
        assertTrue(Files.exists(oldProduct));
        assertTrue(Files.exists(oldLogo));
        assertTrue(Files.exists(young));
        assertTrue(Files.exists(foreign));
    }

    private static Path aged(Path folder, String name, Instant modified) throws IOException {
        Path file = Files.write(folder.resolve(name), PNG);
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.from(modified));
        return file;
    }

    private static long fileCount(Path folder) throws IOException {
        if (!Files.exists(folder)) return 0;
        try (Stream<Path> files = Files.list(folder)) {
            return files.count();
        }
    }
}
