package com.example.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.example.demo.config.StorageProperties;
import com.example.demo.controller.ImageController;
import com.example.demo.service.FileService;
import com.example.demo.service.StorageMigrationRunner;

import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;

/**
 * 실제 MinIO 에 대한 스토리지 스모크 테스트. 일반 빌드/CI 에서는 건너뛴다.
 * 실행: MINIO_IT=true + MINIO_ENDPOINT/ACCESS_KEY/SECRET_KEY/BUCKET 환경변수 설정 후
 *   ./gradlew test --tests com.example.demo.MinioStorageIT
 */
@EnabledIfEnvironmentVariable(named = "MINIO_IT", matches = "true")
class MinioStorageIT {

    private StorageProperties props() {
        StorageProperties p = new StorageProperties();
        p.setEndpoint(System.getenv("MINIO_ENDPOINT"));
        p.setAccessKey(System.getenv("MINIO_ACCESS_KEY"));
        p.setSecretKey(System.getenv("MINIO_SECRET_KEY"));
        p.setBucket(System.getenv("MINIO_BUCKET"));
        return p;
    }

    private MinioClient client(StorageProperties p) {
        return MinioClient.builder()
                .endpoint(p.getEndpoint())
                .credentials(p.getAccessKey(), p.getSecretKey())
                .build();
    }

    private boolean exists(MinioClient c, String bucket, String obj) {
        try {
            c.statObject(StatObjectArgs.builder().bucket(bucket).object(obj).build());
            return true;
        } catch (ErrorResponseException e) {
            return false;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void remove(MinioClient c, String bucket, String obj) {
        try {
            c.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(obj).build());
        } catch (Exception ignore) {
        }
    }

    @Test
    void storeServeRenameRoundTrip() throws Exception {
        StorageProperties p = props();
        MinioClient c = client(p);
        FileService svc = new FileService(c, p);

        byte[] content = "hello-minio-이미지".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile(
                "file", "it-verify.png", "image/png", content);

        String name = svc.storeFile(file);
        String renamed = "it-verify-renamed.png";
        try {
            // 1) 저장됨
            assertThat(name).endsWith("_it-verify.png");
            assertThat(svc.fileExists(name)).isTrue();
            assertThat(exists(c, p.getBucket(), name)).isTrue();

            // 2) 서빙/재업로드용 바이트가 일치
            assertThat(svc.loadFileBytes(name)).isEqualTo(content);

            // 3) URL 포맷 유지
            assertThat(svc.getFileUrl(name)).isEqualTo("/uploads/" + name);

            // 4) 존재하지 않는 파일
            assertThat(svc.fileExists("no-such-object-xyz.png")).isFalse();

            // 5) 이름 변경(copy+remove)
            String result = svc.renameFile(name, renamed);
            assertThat(result).isEqualTo(renamed);
            assertThat(svc.fileExists(renamed)).isTrue();
            assertThat(svc.fileExists(name)).isFalse();
            assertThat(svc.loadFileBytes(renamed)).isEqualTo(content);
        } finally {
            remove(c, p.getBucket(), name);
            remove(c, p.getBucket(), renamed);
        }
    }

    @Test
    void serveEndpointStreamsBytesFromMinio() throws Exception {
        StorageProperties p = props();
        MinioClient c = client(p);
        FileService svc = new FileService(c, p);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ImageController(c, p)).build();

        byte[] content = new byte[] {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3, 4, 5};
        MockMultipartFile file = new MockMultipartFile(
                "file", "it-serve.png", "image/png", content);
        String name = svc.storeFile(file);
        try {
            MvcResult res = mvc.perform(get("/uploads/{f}", name))
                    .andExpect(status().isOk())
                    .andReturn();
            assertThat(res.getResponse().getContentType()).isEqualTo("image/png");
            assertThat(res.getResponse().getContentAsByteArray()).isEqualTo(content);

            // 존재하지 않는 파일은 404
            mvc.perform(get("/uploads/{f}", "no-such-object-xyz.png"))
                    .andExpect(status().isNotFound());
        } finally {
            remove(c, p.getBucket(), name);
        }
    }

    @Test
    void migrationUploadsLocalFiles() throws Exception {
        StorageProperties p = props();
        MinioClient c = client(p);

        Path dir = Files.createTempDirectory("minio-mig-it");
        String obj = "it-migrate-sample.txt";
        Path f = dir.resolve(obj);
        byte[] content = "migrate-me".getBytes(StandardCharsets.UTF_8);
        Files.write(f, content);

        p.setMigrateLocalDir(dir.toString());
        StorageMigrationRunner runner = new StorageMigrationRunner(c, p);
        try {
            runner.run();
            assertThat(exists(c, p.getBucket(), obj)).isTrue();
        } finally {
            remove(c, p.getBucket(), obj);
            Files.deleteIfExists(f);
            Files.deleteIfExists(dir);
        }
    }
}
