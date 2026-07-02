package com.example.demo.controller;

import java.io.InputStream;
import java.time.Duration;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.config.StorageProperties;

import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 업로드 이미지를 /uploads/&lt;파일명&gt; 으로 서빙한다. 실제 바이트는 MinIO 에서 스트리밍한다.
 * (기존 로컬 디스크 정적 서빙을 대체 — DB/프론트에 저장된 URL 포맷은 그대로 유지)
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ImageController {

    private final MinioClient minioClient;
    private final StorageProperties props;

    @GetMapping("/uploads/{filename:.+}")
    public ResponseEntity<InputStreamResource> serve(@PathVariable String filename) {
        StatObjectResponse stat;
        try {
            stat = minioClient.statObject(StatObjectArgs.builder()
                    .bucket(props.getBucket())
                    .object(filename)
                    .build());
        } catch (ErrorResponseException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.warn("이미지 조회 실패: {} ({})", filename, e.getMessage());
            return ResponseEntity.status(502).build();
        }

        try {
            InputStream in = minioClient.getObject(GetObjectArgs.builder()
                    .bucket(props.getBucket())
                    .object(filename)
                    .build());

            MediaType contentType = resolveContentType(stat.contentType(), filename);

            return ResponseEntity.ok()
                    .contentType(contentType)
                    .contentLength(stat.size())
                    .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic())
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                    .body(new InputStreamResource(in));
        } catch (Exception e) {
            log.warn("이미지 스트리밍 실패: {} ({})", filename, e.getMessage());
            return ResponseEntity.status(502).build();
        }
    }

    private MediaType resolveContentType(String stored, String filename) {
        if (stored != null && !stored.isBlank() && !"application/octet-stream".equalsIgnoreCase(stored)) {
            try {
                return MediaType.parseMediaType(stored);
            } catch (RuntimeException ignore) {
                // 저장된 값이 이상하면 확장자로 추론
            }
        }
        String lower = filename.toLowerCase();
        if (lower.endsWith(".png")) {
            return MediaType.IMAGE_PNG;
        }
        if (lower.endsWith(".gif")) {
            return MediaType.IMAGE_GIF;
        }
        if (lower.endsWith(".webp")) {
            return MediaType.parseMediaType("image/webp");
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return MediaType.IMAGE_JPEG;
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
