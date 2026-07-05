# kiosk-backend

무인 주문 시스템(비전 인식)의 코어 백엔드 — **Spring Boot · PostgreSQL · MinIO · 외부 ERP(MSSQL) · 네이버커머스 연동**

부모님 가게의 인력난을 덜기 위해 기획부터 배포·운영까지 1인으로 개발한 프로젝트입니다.
상품·주문·카테고리 관리, ERP 동기화, 판매 채널 연동, 이미지 스토리지를 담당합니다.

## 시스템 구성

전체 시스템은 4개 서비스로 구성되며, 이 저장소는 그중 코어 백엔드입니다.

| 서비스 | 역할 | 저장소 |
| --- | --- | --- |
| **kiosk-backend** | 상품·주문·ERP 동기화·채널 연동 API | (이 저장소) |
| kiosk-frontend | 주문·관리자 UI (React) | [bapdodi/kiosk-frontend](https://github.com/bapdodi/kiosk-frontend) |
| vision-backend | YOLOv8 상품 인식 · 자가학습 루프 | 비공개 |
| vision-frontend | 비전 검사 UI | 비공개 |

```mermaid
flowchart LR
  FE[kiosk-frontend] --> BE[kiosk-backend]
  BE --> PG[(PostgreSQL)]
  BE --> MINIO[(MinIO<br/>상품 이미지)]
  BE --> ERP[(외부 ERP<br/>MSSQL)]
  BE --> NAVER[네이버커머스 API]
```

## 핵심 설계

- **판매 채널 연동을 Strategy 패턴으로 추상화** — `SalesChannelConnector` 인터페이스에 채널별 구현체를 붙이는 구조로, 네이버커머스를 연동했고 새 채널 추가 비용은 "구현체 1개"입니다. (`service/channel`, `service/naver`)
- **ERP 옵션조합 동기화 최적화** — 매 동기화마다 전체삭제+재삽입하던 것을 `erpCode` 맵 매칭으로 변경분만 in-place 갱신하고, 상품별 컬렉션은 `@BatchSize(500)` IN 배치 로딩으로 N+1을 제거했습니다. (`ErpSyncService`)
- **ERP 전표번호 계산 최소화** — 품목마다 외부 ERP에 `SELECT MAX(dNO)`를 반복하던 것을 주문 진입 시 1회 계산·고정으로 바꿔 외부 DB 조회를 품목 N회 → 주문당 1회로 줄였습니다.
- **LexoRank 기반 상품 정렬** — 순서 변경 시 두 이웃 사이의 rank 문자열만 갱신하는 O(1) 재정렬로, 드래그앤드롭 낙관적 UI를 지원합니다. (`util/LexoRank`)
- **이미지 캐싱** — 상품 이미지에 `Cache-Control` 7일 + ETag를 부여해 재방문 시 304로 처리합니다. 이미지는 MinIO 오브젝트 스토리지에 저장하며, 로컬 파일 → MinIO 이관용 `StorageMigrationRunner`를 포함합니다.
- **배포** — CI 배포 잡 대신 Watchtower 폴링으로 컨테이너 이미지를 자동 갱신합니다(과거 배포 잡이 운영 `.env`를 덮어쓰던 위험 제거).

## 기술 스택

Java 17 · Spring Boot (Web, Data JPA, Security, Validation) · PostgreSQL · MSSQL(외부 ERP) · MinIO · Docker / docker-compose · Watchtower

## 실행

```bash
# 로컬 (PostgreSQL + ERP용 MSSQL 컨테이너 포함)
docker compose up -d
./gradlew bootRun
```

주요 환경변수 (기본값은 `src/main/resources/application.yml` 참고):

| 변수 | 설명 |
| --- | --- |
| `SPRING_DATASOURCE_URL / USERNAME / PASSWORD` | 서비스 DB (PostgreSQL) |
| `ERP_DATASOURCE_URL / USERNAME / PASSWORD` | 외부 ERP DB (MSSQL) |
| `MINIO_ENDPOINT / ACCESS_KEY / SECRET_KEY / BUCKET` | 이미지 스토리지 |
| `NAVER_COMMERCE_CLIENT_ID / CLIENT_SECRET` | 네이버커머스 API |

운영 배포는 `docker-compose.prod.yml` + Watchtower를 사용합니다.
