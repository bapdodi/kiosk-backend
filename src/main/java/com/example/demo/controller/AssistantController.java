package com.example.demo.controller;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.service.AssistantService;
import com.fasterxml.jackson.databind.JsonNode;

import lombok.RequiredArgsConstructor;

/** 관리자 AI 도우미. 경로에 /admin/ 이 들어가야 SecurityConfig 의 ADMIN 규칙이 걸린다. */
@RestController
@RequestMapping("/api/assistant/admin")
@RequiredArgsConstructor
public class AssistantController {

    private static final Logger log = LoggerFactory.getLogger(AssistantController.class);

    private final AssistantService assistantService;

    public record ChatRequest(List<AssistantService.Message> messages) {}

    public record ExecuteActionRequest(String actionType, JsonNode payload) {}

    @PostMapping("/chat")
    public ResponseEntity<?> chat(@RequestBody ChatRequest request) {
        try {
            return ResponseEntity.ok(assistantService.chat(request.messages()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
        } catch (IllegalStateException e) {
            // 503 은 프런트(apiError)가 "백엔드 꺼짐"으로 읽으므로, 본문 있는 500 으로 원인 문구를 그대로 보여 준다.
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    /** AI 도우미가 제시한 액션 카드를 사장님이 화면에서 [승인 및 실행] 했을 때 호출된다. */
    @PostMapping("/action/execute")
    public ResponseEntity<?> executeAction(@RequestBody ExecuteActionRequest request, Authentication auth) {
        try {
            String actor = auth != null && auth.getName() != null ? auth.getName() : "admin";
            return ResponseEntity.ok(assistantService.executeAction(request.actionType(), request.payload(), actor));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
        } catch (Exception e) {
            log.error("AI 도우미 액션 실행 실패", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "실행 중 오류가 발생했습니다: " + e.getMessage()));
        }
    }

    /** 이전 세션 대화 기록(DB assistant_log)을 최신순으로 조회한다. */
    @GetMapping("/history")
    public ResponseEntity<?> getHistory(@RequestParam(value = "limit", defaultValue = "30") int limit) {
        return ResponseEntity.ok(assistantService.getRecentLogs(limit));
    }
}
