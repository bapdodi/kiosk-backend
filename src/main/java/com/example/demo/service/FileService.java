package com.example.demo.service;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import com.example.demo.config.StorageProperties;

import io.minio.CopyObjectArgs;
import io.minio.CopySource;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;

import lombok.RequiredArgsConstructor;

/**
 * 업로드 이미지 저장소. MinIO(S3 호환)에 객체 키 = 파일명 으로 저장한다.
 * 서빙 URL 은 기존과 동일하게 /uploads/&lt;파일명&gt; 이며 백엔드가 MinIO 에서 스트리밍한다.
 */
@Service
@RequiredArgsConstructor
public class FileService {

    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    private final MinioClient minioClient;
    private final StorageProperties props;

    public String storeFile(MultipartFile file) throws IOException {
        String originalFileName = StringUtils.cleanPath(file.getOriginalFilename());
        String fileName = UUID.randomUUID().toString() + "_" + originalFileName;

        String contentType = file.getContentType();
        if (contentType == null || contentType.isBlank()) {
            contentType = DEFAULT_CONTENT_TYPE;
        }

        try (InputStream in = file.getInputStream()) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(props.getBucket())
                    .object(fileName)
                    .stream(in, file.getSize(), -1)
                    .contentType(contentType)
                    .build());
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("MinIO 업로드 실패: " + fileName, e);
        }

        return fileName;
    }

    public String getFileUrl(String fileName) {
        return "/uploads/" + fileName;
    }

    /** 객체를 새 이름으로 복사 후 원본을 삭제한다. 원본이 없으면 기존 이름을 그대로 반환한다. */
    public String renameFile(String oldFileName, String newFileName) throws IOException {
        if (oldFileName.equals(newFileName)) {
            return newFileName;
        }
        if (!fileExists(oldFileName)) {
            return oldFileName;
        }
        try {
            minioClient.copyObject(CopyObjectArgs.builder()
                    .bucket(props.getBucket())
                    .object(newFileName)
                    .source(CopySource.builder()
                            .bucket(props.getBucket())
                            .object(oldFileName)
                            .build())
                    .build());
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(props.getBucket())
                    .object(oldFileName)
                    .build());
            return newFileName;
        } catch (Exception e) {
            throw new IOException("MinIO 이름 변경 실패: " + oldFileName + " -> " + newFileName, e);
        }
    }

    /** 객체 바이트를 읽는다. (네이버 이미지 재업로드용) */
    public byte[] loadFileBytes(String fileName) throws IOException {
        try (InputStream in = minioClient.getObject(GetObjectArgs.builder()
                .bucket(props.getBucket())
                .object(fileName)
                .build())) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("MinIO 객체 읽기 실패: " + fileName, e);
        }
    }

    public boolean fileExists(String fileName) {
        try {
            minioClient.statObject(StatObjectArgs.builder()
                    .bucket(props.getBucket())
                    .object(fileName)
                    .build());
            return true;
        } catch (ErrorResponseException e) {
            // NoSuchKey 등: 존재하지 않음
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
