package com.example.demo.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.example.demo.service.ErpBakImportService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * ERP 백업(.bak) 반영 화면용 API.
 * 경로에 /admin/ 이 들어가야 SecurityConfig 의 ADMIN 규칙(/api/*&#47;admin/**)이 걸린다.
 */
@RestController
@RequestMapping("/api/erp-bak/admin")
@RequiredArgsConstructor
@Slf4j
public class ErpBakImportController {

    private final ErpBakImportService erpBakImportService;

    /** 적용 요청. codes 가 비어 있으면 400 으로 막는다(전체 적용은 화면에서 전체 선택으로 보낸다). */
    public record ApplyRequest(List<Integer> codes, Boolean syncProducts) {}

    public record BeginUploadRequest(String fileName, long totalSize) {}

    public record FinishUploadRequest(String uploadId) {}

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return handle(() -> erpBakImportService.status());
    }

    /**
     * 업로드는 세 단계로 나눈다. Cloudflare 가 요청 본문을 100MB 로 막아서 수백 MB .bak 을
     * 한 번에 보낼 수 없기 때문이다. begin 으로 받은 chunkSize 만큼 잘라 순서대로 chunk 를
     * 보내고 finish 를 호출하면 서버가 이어붙인 파일을 스테이징 DB 로 복원한다.
     */
    @PostMapping("/upload/begin")
    public ResponseEntity<?> beginUpload(@RequestBody BeginUploadRequest request) {
        return handle(() -> erpBakImportService.beginUpload(request.fileName(), request.totalSize()));
    }

    @PostMapping("/upload/chunk")
    public ResponseEntity<?> uploadChunk(@RequestParam("uploadId") String uploadId,
            @RequestParam("index") int index,
            @RequestParam("file") MultipartFile chunk) {
        return handle(() -> {
            erpBakImportService.appendChunk(uploadId, index, chunk);
            return Map.of("received", index + 1);
        });
    }

    @PostMapping("/upload/finish")
    public ResponseEntity<?> finishUpload(@RequestBody FinishUploadRequest request) {
        return handle(() -> erpBakImportService.finishUpload(request.uploadId()));
    }

    @DeleteMapping("/upload/{uploadId}")
    public ResponseEntity<?> abortUpload(@PathVariable String uploadId) {
        return handle(() -> {
            erpBakImportService.abortUpload(uploadId);
            return Map.of("aborted", true);
        });
    }

    @GetMapping("/diff")
    public ResponseEntity<?> diff() {
        return handle(() -> erpBakImportService.diff());
    }

    @PostMapping("/apply")
    public ResponseEntity<?> apply(@RequestBody ApplyRequest request) {
        return handle(() -> erpBakImportService.apply(
                request.codes() == null ? List.of() : request.codes(),
                request.syncProducts() == null || request.syncProducts()));
    }

    @DeleteMapping("/staging")
    public ResponseEntity<?> discard() {
        return handle(() -> {
            erpBakImportService.discard();
            return Map.of("discarded", true);
        });
    }

    /** 사용자가 고칠 수 있는 입력 오류는 400, 나머지는 500 으로 내리고 메시지는 그대로 화면에 띄운다. */
    private ResponseEntity<?> handle(ThrowingSupplier supplier) {
        try {
            return ResponseEntity.ok(supplier.get());
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
        } catch (Exception e) {
            log.error("ERP 백업 반영 처리 실패", e);
            return ResponseEntity.status(500).body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier {
        Object get() throws Exception;
    }
}
