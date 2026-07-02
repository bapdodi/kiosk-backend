package com.example.demo.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** MinIO 클라이언트 빈과 버킷 준비를 담당한다. */
@Slf4j
@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(StorageProperties.class)
public class MinioConfig {

    private final StorageProperties props;

    @Bean
    public MinioClient minioClient() {
        return MinioClient.builder()
                .endpoint(props.getEndpoint())
                .credentials(props.getAccessKey(), props.getSecretKey())
                .build();
    }

    /**
     * 부팅 시 버킷 존재를 확인하고 없으면 생성한다.
     * MinIO 가 일시적으로 불가하더라도 앱 기동은 막지 않는다(경고만 남긴다).
     */
    @PostConstruct
    public void ensureBucket() {
        try {
            MinioClient client = minioClient();
            boolean exists = client.bucketExists(
                    BucketExistsArgs.builder().bucket(props.getBucket()).build());
            if (!exists) {
                client.makeBucket(MakeBucketArgs.builder().bucket(props.getBucket()).build());
                log.info("MinIO 버킷 생성: {}", props.getBucket());
            } else {
                log.info("MinIO 버킷 확인: {} (endpoint={})", props.getBucket(), props.getEndpoint());
            }
        } catch (Exception e) {
            log.warn("MinIO 버킷 준비 실패 (이미지 저장/서빙이 동작하지 않을 수 있음): {}", e.getMessage());
        }
    }
}
