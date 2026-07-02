package com.example.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/** MinIO(S3 호환) 저장소 설정. application.yml 의 storage.minio.* 에 매핑된다. */
@Getter
@Setter
@ConfigurationProperties(prefix = "storage.minio")
public class StorageProperties {

    /** MinIO 엔드포인트 (예: http://192.168.0.90:9000). */
    private String endpoint;

    /** 액세스 키. */
    private String accessKey;

    /** 시크릿 키. */
    private String secretKey;

    /** 업로드 이미지를 저장할 버킷명. */
    private String bucket;

    /**
     * 지정 시 부팅 때 이 로컬 디렉터리의 파일을 MinIO 로 1회 업로드한다(누락분만).
     * 비어 있으면 마이그레이션을 수행하지 않는다.
     */
    private String migrateLocalDir;
}
