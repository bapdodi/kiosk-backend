package com.example.demo.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import lombok.extern.slf4j.Slf4j;

/**
 * ERP 백업(.bak)을 스테이징 DB 로 복원해 현재 ERP 와 ITEM 차이를 보여주고, 선택한 품목만 반영한다.
 *
 * 흐름: 업로드 → 스테이징 복원 → 차이 조회 → 선택 적용(백업 후 교체) → 스테이징 정리.
 * RESTORE 는 SQL Server 자기 파일시스템의 파일만 읽는데 T-SQL 로는 클라이언트가 보낸 바이트를
 * 서버 디스크에 쓸 수 없다. 그래서 백엔드가 MSSQL 데이터 볼륨(mssql_data)을 같이 물고
 * /var/opt/mssql/import 아래에 .bak 을 놓는다. 두 컨테이너에서 보이는 경로가 다를 수 있어
 * 업로드 경로와 MSSQL 이 보는 경로를 각각 설정으로 받는다.
 */
@Service
@Slf4j
public class ErpBakImportService {

    /** 키오스크 동기화 대상과 동일한 범위. 100 미만은 ERP 내부용 코드라 비교/적용에서 제외한다. */
    private static final int MIN_CODE = 100;

    /** 차이로 볼 컬럼. 재고(JEGO)는 수시로 변해 목록이 의미를 잃으므로 제외하고, 적용 시에는 행 전체가 바뀐다. */
    private static final List<String> COMPARED_COLUMNS = List.of(
            "ITEM", "GYU", "OUTA", "OUTB", "OUTC", "PARTCODE", "MIDCODE", "SMALLCODE");

    /** 중간에 끊긴 업로드 조각을 치우는 기준 나이. */
    private static final long STALE_PART_AGE_MS = 6 * 60 * 60 * 1000L;

    private static final String DROP_TEMP_CODES =
            "IF OBJECT_ID('tempdb..#erp_apply_codes') IS NOT NULL DROP TABLE #erp_apply_codes";

    private static final DateTimeFormatter BACKUP_SUFFIX = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final JdbcTemplate erpJdbcTemplate;
    private final JdbcTemplate erpMasterJdbcTemplate;
    private final ErpSyncService erpSyncService;

    private final String stagingDb;
    private final Path uploadDir;
    private final String mssqlDir;
    private final int chunkSize;

    public ErpBakImportService(
            @Qualifier("erpJdbcTemplate") JdbcTemplate erpJdbcTemplate,
            @Qualifier("erpMasterJdbcTemplate") JdbcTemplate erpMasterJdbcTemplate,
            ErpSyncService erpSyncService,
            @Value("${erp.bak-import.staging-db:DR_ERP_STAGING}") String stagingDb,
            @Value("${erp.bak-import.upload-dir:/var/opt/mssql/import}") String uploadDir,
            @Value("${erp.bak-import.mssql-dir:/var/opt/mssql/import}") String mssqlDir,
            // Cloudflare 무료 플랜의 요청 본문 상한이 100MB 라 그보다 넉넉히 아래로 자른다.
            @Value("${erp.bak-import.chunk-size:67108864}") int chunkSize) {
        this.erpJdbcTemplate = erpJdbcTemplate;
        this.erpMasterJdbcTemplate = erpMasterJdbcTemplate;
        this.erpSyncService = erpSyncService;
        this.stagingDb = stagingDb;
        this.uploadDir = Paths.get(uploadDir);
        this.mssqlDir = mssqlDir;
        this.chunkSize = chunkSize;
    }

    // ── 응답 타입 ────────────────────────────────────────────────────────────

    /** 스테이징 상태. uploadedAt 은 .bak 파일의 수정 시각. */
    public record StagingStatus(boolean staged, String fileName, Long fileSize, String uploadedAt, Integer itemRows) {}

    /** 차이 한 건. 변경 건은 before/after 가 모두 차고, 추가는 before 가, 삭제는 after 가 null 이다. */
    public record ItemDiff(int code, String kind, String name, String spec,
                           Map<String, Object> before, Map<String, Object> after,
                           List<String> changedFields) {}

    public record DiffResult(List<ItemDiff> added, List<ItemDiff> removed, List<ItemDiff> changed,
                             int prodRows, int stagingRows) {}

    public record ApplyResult(int applied, int inserted, int deleted, String backupTable, Integer syncedProducts) {}

    // ── 1. 업로드(청크) + 스테이징 복원 ──────────────────────────────────────

    /** 업로드 시작 응답. 클라이언트는 chunkSize 만큼 잘라 순서대로 올린다. */
    public record UploadSession(String uploadId, int chunkSize) {}

    /** 진행 중인 업로드 하나. 조각을 순서대로 이어붙이므로 다음에 받을 번호와 누적 크기만 들고 있으면 된다. */
    private static final class UploadState {
        private final String fileName;
        private final long totalSize;
        private final Path path;
        private int nextIndex;
        private long received;

        private UploadState(String fileName, long totalSize, Path path) {
            this.fileName = fileName;
            this.totalSize = totalSize;
            this.path = path;
        }
    }

    private final Map<String, UploadState> uploads = new ConcurrentHashMap<>();

    public UploadSession beginUpload(String fileName, long totalSize) throws IOException {
        if (fileName == null || !fileName.toLowerCase().endsWith(".bak")) {
            throw new IllegalArgumentException("MS SQL 백업 파일(.bak)만 올릴 수 있습니다.");
        }
        if (totalSize <= 0) {
            throw new IllegalArgumentException("파일 크기를 확인할 수 없습니다.");
        }
        Files.createDirectories(uploadDir);
        purgeStaleParts();

        String uploadId = UUID.randomUUID().toString();
        Path part = uploadDir.resolve(uploadId + ".part");
        Files.deleteIfExists(part);
        Files.createFile(part);
        uploads.put(uploadId, new UploadState(fileName, totalSize, part));
        log.info("ERP 백업 업로드 시작: {} ({} bytes, uploadId={})", fileName, totalSize, uploadId);
        return new UploadSession(uploadId, chunkSize);
    }

    /** 조각을 이어붙인다. 번호가 어긋나면 조용히 섞이지 않도록 거절한다. */
    public void appendChunk(String uploadId, int index, MultipartFile chunk) throws IOException {
        UploadState state = uploads.get(uploadId);
        if (state == null) {
            throw new IllegalStateException("만료되었거나 없는 업로드입니다. 처음부터 다시 올려주세요.");
        }
        synchronized (state) {
            if (index != state.nextIndex) {
                throw new IllegalStateException(
                        "조각 순서가 어긋났습니다(기대 " + state.nextIndex + ", 받음 " + index + ").");
            }
            if (state.received + chunk.getSize() > state.totalSize) {
                throw new IllegalStateException("보낸 크기가 처음 알린 파일 크기를 넘었습니다.");
            }
            try (var in = chunk.getInputStream();
                    var out = Files.newOutputStream(state.path, StandardOpenOption.APPEND)) {
                in.transferTo(out);
            }
            state.nextIndex++;
            state.received += chunk.getSize();
        }
    }

    /** 마지막 조각까지 받은 뒤 호출. 크기를 확인하고 제자리로 옮겨 스테이징 DB 로 복원한다. */
    public StagingStatus finishUpload(String uploadId) throws IOException {
        UploadState state = uploads.get(uploadId);
        if (state == null) {
            throw new IllegalStateException("만료되었거나 없는 업로드입니다. 처음부터 다시 올려주세요.");
        }
        if (state.received != state.totalSize) {
            throw new IllegalStateException(
                    "파일이 온전하지 않습니다(" + state.received + "/" + state.totalSize + " bytes). 다시 올려주세요.");
        }
        Path target = uploadDir.resolve("staging.bak");
        Files.move(state.path, target, StandardCopyOption.REPLACE_EXISTING);
        // MSSQL 프로세스(uid 10001)가 읽을 수 있어야 한다.
        target.toFile().setReadable(true, false);
        Files.writeString(uploadDir.resolve("staging.name"), state.fileName);
        uploads.remove(uploadId);
        log.info("ERP 백업 업로드 완료: {} ({} bytes, 조각 {}개)", state.fileName, state.received, state.nextIndex);

        restoreStaging(mssqlDir + "/staging.bak");
        return status();
    }

    public void abortUpload(String uploadId) {
        UploadState state = uploads.remove(uploadId);
        if (state != null) {
            try {
                Files.deleteIfExists(state.path);
            } catch (IOException e) {
                log.warn("업로드 조각 정리 실패: {}", e.getMessage());
            }
        }
    }

    /** 중간에 끊긴 업로드가 디스크를 먹지 않게, 시작할 때 오래된 조각 파일을 치운다. */
    private void purgeStaleParts() {
        try (var files = Files.list(uploadDir)) {
            long cutoff = System.currentTimeMillis() - STALE_PART_AGE_MS;
            files.filter(p -> p.getFileName().toString().endsWith(".part"))
                    .filter(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toMillis() < cutoff;
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                            log.info("오래된 업로드 조각 삭제: {}", p.getFileName());
                        } catch (IOException e) {
                            log.warn("업로드 조각 삭제 실패: {}", e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn("업로드 디렉터리 정리 실패: {}", e.getMessage());
        }
    }

    private void restoreStaging(String diskPath) {
        List<Map<String, Object>> fileList = erpMasterJdbcTemplate.queryForList(
                "RESTORE FILELISTONLY FROM DISK = N'" + escape(diskPath) + "'");
        if (fileList.isEmpty()) {
            throw new IllegalStateException("백업 파일에서 파일 목록을 읽지 못했습니다. 손상된 .bak 일 수 있습니다.");
        }

        String dataDir = erpMasterJdbcTemplate.queryForObject(
                "SELECT CAST(SERVERPROPERTY('InstanceDefaultDataPath') AS nvarchar(4000))", String.class);

        StringBuilder moves = new StringBuilder();
        int seq = 0;
        for (Map<String, Object> f : fileList) {
            String logical = String.valueOf(f.get("LogicalName"));
            String type = String.valueOf(f.get("Type"));
            String ext = "L".equalsIgnoreCase(type) ? "_" + seq + ".ldf" : "_" + seq + ".mdf";
            moves.append(", MOVE N'").append(escape(logical)).append("' TO N'")
                 .append(escape(dataDir + stagingDb + ext)).append("'");
            seq++;
        }

        // 직전 스테이징이 남아 있으면 연결을 끊고 덮어쓴다.
        erpMasterJdbcTemplate.execute(
                "IF DB_ID('" + escape(stagingDb) + "') IS NOT NULL "
                        + "ALTER DATABASE " + quote(stagingDb) + " SET SINGLE_USER WITH ROLLBACK IMMEDIATE");
        try {
            erpMasterJdbcTemplate.execute(
                    "RESTORE DATABASE " + quote(stagingDb) + " FROM DISK = N'" + escape(diskPath) + "'"
                            + " WITH REPLACE" + moves);
            erpMasterJdbcTemplate.execute(
                    "ALTER DATABASE " + quote(stagingDb) + " SET MULTI_USER");
        } catch (RuntimeException e) {
            log.error("스테이징 복원 실패: {}", e.getMessage());
            throw new IllegalStateException("백업 복원에 실패했습니다: " + rootMessage(e), e);
        }

        Integer rows = erpMasterJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + quote(stagingDb) + ".dbo.ITEM", Integer.class);
        log.info("스테이징 복원 완료: {} ({}행)", stagingDb, rows);
    }

    // ── 2. 상태 / 차이 ───────────────────────────────────────────────────────

    public StagingStatus status() {
        Integer dbId = erpMasterJdbcTemplate.queryForObject(
                "SELECT DB_ID('" + escape(stagingDb) + "')", Integer.class);
        if (dbId == null) {
            return new StagingStatus(false, null, null, null, null);
        }
        String name = null;
        Long size = null;
        String uploadedAt = null;
        try {
            Path bak = uploadDir.resolve("staging.bak");
            if (Files.exists(bak)) {
                size = Files.size(bak);
                uploadedAt = LocalDateTime.ofInstant(
                        Files.getLastModifiedTime(bak).toInstant(), java.time.ZoneId.systemDefault()).toString();
            }
            Path nameFile = uploadDir.resolve("staging.name");
            if (Files.exists(nameFile)) {
                name = Files.readString(nameFile).trim();
            }
        } catch (IOException e) {
            log.warn("스테이징 파일 정보를 읽지 못했습니다: {}", e.getMessage());
        }
        Integer rows = erpMasterJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + quote(stagingDb) + ".dbo.ITEM", Integer.class);
        return new StagingStatus(true, name, size, uploadedAt, rows);
    }

    public DiffResult diff() {
        requireStaging();
        String erpDb = currentErpDb();
        String prod = quote(erpDb) + ".dbo.ITEM";
        String stage = quote(stagingDb) + ".dbo.ITEM";
        String cols = COMPARED_COLUMNS.stream().map(this::quote).collect(Collectors.joining(","));
        // 매장 백업본(Korean_Wansung_CI_AS)과 운영 DB 의 collation 이 다르면 교차 DB 문자열 비교가 막힌다.
        // 양쪽을 현재 DB 기본 collation 으로 맞춰 비교한다(nvarchar 라 값 자체는 그대로다).
        Set<String> charColumns = charColumns(erpDb);
        String prodCols = COMPARED_COLUMNS.stream()
                .map(c -> collated("p", c, charColumns)).collect(Collectors.joining(","));
        String stageCols = COMPARED_COLUMNS.stream()
                .map(c -> collated("s", c, charColumns)).collect(Collectors.joining(","));

        List<Map<String, Object>> addedRows = erpMasterJdbcTemplate.queryForList(
                "SELECT CODE," + cols + " FROM " + stage + " WHERE CODE >= " + MIN_CODE
                        + " AND NOT EXISTS (SELECT 1 FROM " + prod + " p WHERE p.CODE = " + stage + ".CODE)"
                        + " ORDER BY CODE");
        List<Map<String, Object>> removedRows = erpMasterJdbcTemplate.queryForList(
                "SELECT CODE," + cols + " FROM " + prod + " WHERE CODE >= " + MIN_CODE
                        + " AND NOT EXISTS (SELECT 1 FROM " + stage + " s WHERE s.CODE = " + prod + ".CODE)"
                        + " ORDER BY CODE");
        // EXCEPT 는 NULL 도 값으로 비교해 준다(<> 와 달리 NULL 차이를 놓치지 않음).
        List<Map<String, Object>> changedRows = erpMasterJdbcTemplate.queryForList(
                "SELECT p.CODE,"
                        + COMPARED_COLUMNS.stream().map(c -> "p." + quote(c) + " AS old_" + c).collect(Collectors.joining(","))
                        + "," + COMPARED_COLUMNS.stream().map(c -> "s." + quote(c) + " AS new_" + c).collect(Collectors.joining(","))
                        + " FROM " + prod + " p JOIN " + stage + " s ON p.CODE = s.CODE"
                        + " WHERE p.CODE >= " + MIN_CODE
                        + " AND EXISTS (SELECT " + prodCols + " EXCEPT SELECT " + stageCols + ")"
                        + " ORDER BY p.CODE");

        List<ItemDiff> added = addedRows.stream().map(r -> toDiff(r, "added")).toList();
        List<ItemDiff> removed = removedRows.stream().map(r -> toDiff(r, "removed")).toList();
        List<ItemDiff> changed = changedRows.stream().map(this::toChangedDiff).toList();

        Integer prodRows = erpMasterJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + prod + " WHERE CODE >= " + MIN_CODE, Integer.class);
        Integer stagingRows = erpMasterJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + stage + " WHERE CODE >= " + MIN_CODE, Integer.class);
        return new DiffResult(added, removed, changed,
                prodRows == null ? 0 : prodRows, stagingRows == null ? 0 : stagingRows);
    }

    private ItemDiff toDiff(Map<String, Object> row, String kind) {
        int code = ((Number) row.get("CODE")).intValue();
        Map<String, Object> values = new java.util.LinkedHashMap<>();
        for (String c : COMPARED_COLUMNS) {
            values.put(c, row.get(c));
        }
        String name = trim(values.get("ITEM"));
        String spec = trim(values.get("GYU"));
        return "added".equals(kind)
                ? new ItemDiff(code, kind, name, spec, null, values, List.of())
                : new ItemDiff(code, kind, name, spec, values, null, List.of());
    }

    private ItemDiff toChangedDiff(Map<String, Object> row) {
        int code = ((Number) row.get("CODE")).intValue();
        Map<String, Object> before = new java.util.LinkedHashMap<>();
        Map<String, Object> after = new java.util.LinkedHashMap<>();
        List<String> changedFields = new ArrayList<>();
        for (String c : COMPARED_COLUMNS) {
            Object oldValue = row.get("old_" + c);
            Object newValue = row.get("new_" + c);
            before.put(c, oldValue);
            after.put(c, newValue);
            if (!java.util.Objects.equals(String.valueOf(oldValue), String.valueOf(newValue))) {
                changedFields.add(c);
            }
        }
        return new ItemDiff(code, "changed", trim(after.get("ITEM")), trim(after.get("GYU")),
                before, after, changedFields);
    }

    // ── 3. 적용 ──────────────────────────────────────────────────────────────

    /**
     * 선택한 CODE 만 스테이징 내용으로 맞춘다. 적용 전 ITEM 전체를 ITEM_bak_&lt;시각&gt; 으로 복사해 둔다.
     * 코드별로 운영 행을 지우고 스테이징에 있으면 다시 넣기 때문에 추가/변경/삭제가 한 경로로 처리된다.
     */
    public ApplyResult apply(Collection<Integer> codes, boolean runProductSync) {
        requireStaging();
        Set<Integer> targets = new LinkedHashSet<>(codes);
        targets.removeIf(c -> c == null || c < MIN_CODE);
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("적용할 품목을 하나 이상 선택해주세요.");
        }

        String erpDb = currentErpDb();
        String prod = quote(erpDb) + ".dbo.ITEM";
        String stage = quote(stagingDb) + ".dbo.ITEM";
        String backupTable = "ITEM_bak_" + LocalDateTime.now().format(BACKUP_SUFFIX);
        List<String> columns = sharedColumns(erpDb);
        String colList = columns.stream().map(this::quote).collect(Collectors.joining(","));
        boolean identity = hasIdentity(erpDb);

        int[] counts = erpMasterJdbcTemplate.execute((Connection conn) -> {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.execute("SET XACT_ABORT ON");
                // 임시 테이블은 연결 단위로 살아 있고 커넥션은 풀에 재사용되므로,
                // 만들기 전에 이전 호출이 남긴 것을 지우고 끝나면 다시 지운다.
                st.execute(DROP_TEMP_CODES);
                st.execute("CREATE TABLE #erp_apply_codes (CODE int PRIMARY KEY)");
                for (List<Integer> chunk : partition(new ArrayList<>(targets), 500)) {
                    st.execute("INSERT INTO #erp_apply_codes (CODE) VALUES "
                            + chunk.stream().map(c -> "(" + c + ")").collect(Collectors.joining(",")));
                }
                st.execute("SELECT * INTO " + quote(erpDb) + ".dbo." + quote(backupTable)
                        + " FROM " + prod);
                int deleted = st.executeUpdate(
                        "DELETE FROM " + prod + " WHERE CODE IN (SELECT CODE FROM #erp_apply_codes)");
                if (identity) {
                    st.execute("SET IDENTITY_INSERT " + prod + " ON");
                }
                int inserted = st.executeUpdate(
                        "INSERT INTO " + prod + " (" + colList + ") SELECT " + colList
                                + " FROM " + stage + " WHERE CODE IN (SELECT CODE FROM #erp_apply_codes)");
                if (identity) {
                    st.execute("SET IDENTITY_INSERT " + prod + " OFF");
                }
                conn.commit();
                log.info("ERP ITEM 적용 완료: 선택 {}건, 삭제 {}행, 삽입 {}행, 백업 {}",
                        targets.size(), deleted, inserted, backupTable);
                return new int[] { deleted, inserted };
            } catch (Exception e) {
                conn.rollback();
                log.error("ERP ITEM 적용 실패, 롤백했습니다: {}", e.getMessage());
                throw new IllegalStateException("적용에 실패해 되돌렸습니다: " + rootMessage(e), e);
            } finally {
                try (Statement cleanup = conn.createStatement()) {
                    cleanup.execute(DROP_TEMP_CODES);
                } catch (Exception ignored) {
                    // 정리 실패는 다음 호출의 선(先)삭제가 받아준다.
                }
                conn.setAutoCommit(autoCommit);
            }
        });

        Integer synced = null;
        if (runProductSync) {
            synced = erpSyncService.syncProducts().size();
        }
        return new ApplyResult(targets.size(), counts[1], counts[0], backupTable, synced);
    }

    // ── 4. 정리 ──────────────────────────────────────────────────────────────

    public void discard() {
        erpMasterJdbcTemplate.execute(
                "IF DB_ID('" + escape(stagingDb) + "') IS NOT NULL BEGIN "
                        + "ALTER DATABASE " + quote(stagingDb) + " SET SINGLE_USER WITH ROLLBACK IMMEDIATE; "
                        + "DROP DATABASE " + quote(stagingDb) + "; END");
        try {
            Files.deleteIfExists(uploadDir.resolve("staging.bak"));
            Files.deleteIfExists(uploadDir.resolve("staging.name"));
        } catch (IOException e) {
            log.warn("업로드 파일 정리 실패: {}", e.getMessage());
        }
        log.info("스테이징 정리 완료");
    }

    // ── 내부 유틸 ────────────────────────────────────────────────────────────

    private void requireStaging() {
        Integer dbId = erpMasterJdbcTemplate.queryForObject(
                "SELECT DB_ID('" + escape(stagingDb) + "')", Integer.class);
        if (dbId == null) {
            throw new IllegalStateException("먼저 .bak 파일을 올려주세요.");
        }
    }

    private String currentErpDb() {
        return erpJdbcTemplate.queryForObject("SELECT DB_NAME()", String.class);
    }

    /** 운영과 스테이징 양쪽에 모두 있는 컬럼만 옮긴다. 백업본 스키마가 조금 달라도 적용이 막히지 않게. */
    private List<String> sharedColumns(String erpDb) {
        List<String> shared = erpMasterJdbcTemplate.queryForList(
                "SELECT p.COLUMN_NAME FROM " + quote(erpDb) + ".INFORMATION_SCHEMA.COLUMNS p"
                        + " JOIN " + quote(stagingDb) + ".INFORMATION_SCHEMA.COLUMNS s"
                        + "   ON s.TABLE_NAME COLLATE DATABASE_DEFAULT = p.TABLE_NAME COLLATE DATABASE_DEFAULT"
                        + "  AND s.COLUMN_NAME COLLATE DATABASE_DEFAULT = p.COLUMN_NAME COLLATE DATABASE_DEFAULT"
                        + " WHERE p.TABLE_NAME = 'ITEM' ORDER BY p.ORDINAL_POSITION",
                String.class);
        if (shared.isEmpty()) {
            throw new IllegalStateException("ITEM 테이블의 공통 컬럼을 찾지 못했습니다.");
        }
        return shared;
    }

    /** 비교 대상 중 문자형 컬럼. 이 컬럼만 COLLATE 를 붙이면 되고 숫자 컬럼은 collation 과 무관하다. */
    private Set<String> charColumns(String erpDb) {
        List<String> names = erpMasterJdbcTemplate.queryForList(
                "SELECT COLUMN_NAME FROM " + quote(erpDb) + ".INFORMATION_SCHEMA.COLUMNS"
                        + " WHERE TABLE_NAME = 'ITEM' AND DATA_TYPE IN ('char','varchar','nchar','nvarchar','text','ntext')",
                String.class);
        return new LinkedHashSet<>(names);
    }

    private String collated(String alias, String column, Set<String> charColumns) {
        return alias + "." + quote(column) + (charColumns.contains(column) ? " COLLATE DATABASE_DEFAULT" : "");
    }

    private boolean hasIdentity(String erpDb) {
        Integer count = erpMasterJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + quote(erpDb) + ".sys.columns"
                        + " WHERE object_id = OBJECT_ID('" + escape(erpDb) + ".dbo.ITEM') AND is_identity = 1",
                Integer.class);
        return count != null && count > 0;
    }

    private static <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> chunks = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            chunks.add(list.subList(i, Math.min(list.size(), i + size)));
        }
        return chunks;
    }

    private String quote(String identifier) {
        return "[" + identifier.replace("]", "]]") + "]";
    }

    private static String escape(String literal) {
        return literal.replace("'", "''");
    }

    private static String trim(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage();
    }
}
