package com.example.demo.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 관리자 AI 도우미 질문 한 건. 실패한 질문은 answer 에 오류 문구가 들어간다. */
@Entity
@Table(name = "assistant_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AssistantLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false, columnDefinition = "text")
    private String question;

    /** 호출한 조회 도구와 인자. 예: erp_sales_summary{"from":"2026-10-01"} */
    @Builder.Default
    @Column(nullable = false, length = 1000)
    private String tools = "";

    @Column(nullable = false, columnDefinition = "text")
    private String answer;
}
