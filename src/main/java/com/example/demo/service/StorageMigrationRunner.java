package com.example.demo.service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import com.example.demo.config.StorageProperties;

import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 1회성 마이그레이션: storage.minio.migrate-local-dir 가 설정되면 부팅 시
 * 해당 로컬 디렉터리의 파일을 MinIO 로 업로드한다(이미 존재하는 객체는 건너뜀).
 * 운영 컨테이너에서는 MINIO_MIGRATE_LOCAL_DIR=/app/uploads 로 1회 배포 후 값을 비운다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StorageMigrationRunner implements CommandLineRunner {

    private final MinioClient minioClient;
    private final StorageProperties props;

    @Override
    public void run(String... args) {
        String dir = props.getMigrateLocalDir();
        if (dir == null || dir.isBlank()) {
            return;
        }

        Path root = Paths.get(dir);
        if (!Files.isDirectory(root)) {
            log.warn("마이그레이션 디렉터리가 없어 건너뜀: {}", dir);
            return;
        }

        log.info("MinIO 마이그레이션 시작: {} -> 버킷 {}", dir, props.getBucket());
        int uploaded = 0;
        int skipped = 0;
        int failed = 0;

        try (Stream<Path> files = Files.walk(root)) {
            for (Path path : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                // 객체 키는 파일명 (기존 URL /uploads/<파일명> 과 일치시켜야 한다)
                String objectName = path.getFileName().toString();
                try {
                    if (objectExists(objectName)) {
                        skipped++;
                        continue;
                    }
                    long size = Files.size(path);
                    String contentType = Files.probeContentType(path);
                    if (contentType == null || contentType.isBlank()) {
                        contentType = "application/octet-stream";
                    }
                    try (InputStream in = Files.newInputStream(path)) {
                        minioClient.putObject(PutObjectArgs.builder()
                                .bucket(props.getBucket())
                                .object(objectName)
                                .stream(in, size, -1)
                                .contentType(contentType)
                                .build());
                    }
                    uploaded++;
                } catch (Exception e) {
                    failed++;
                    log.warn("마이그레이션 실패(건너뜀): {} ({})", objectName, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("마이그레이션 중 디렉터리 순회 실패: {}", e.getMessage());
        }

        log.info("MinIO 마이그레이션 완료: 업로드 {}건, 스킵(이미존재) {}건, 실패 {}건", uploaded, skipped, failed);
    }

    private boolean objectExists(String objectName) {
        try {
            minioClient.statObject(StatObjectArgs.builder()
                    .bucket(props.getBucket())
                    .object(objectName)
                    .build());
            return true;
        } catch (ErrorResponseException e) {
            return false;
        } catch (Exception e) {
            // 확인 불가 시 중복 업로드를 피하기 위해 실패로 간주하지 않고 존재하지 않는 것으로 처리
            return false;
        }
    }
}
