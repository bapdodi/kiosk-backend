package com.example.demo.config;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
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
     * 설정 빈 안의 @PostConstruct 에서 minioClient() 를 부르면 이 빈이 아직 생성 중이라
     * 순환 참조로 실패하므로, 빈이 다 만들어진 뒤 실행되는 러너에서 주입받아 쓴다.
     * MinIO 가 일시적으로 불가하더라도 앱 기동은 막지 않는다(경고만 남긴다).
     */
    @Bean
    public ApplicationRunner ensureBucket(MinioClient client) {
        return args -> checkBucket(client);
    }

    private void checkBucket(MinioClient client) {
        try {
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
