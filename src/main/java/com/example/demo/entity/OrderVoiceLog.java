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

/** 손님용 AI 음성 통화(주문) 한 건의 대화 및 도구 실행 기록. */
@Entity
@Table(name = "order_voice_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderVoiceLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(length = 64)
    private String sessionId;

    @Column(length = 255)
    private String customerName;

    @Builder.Default
    @Column(nullable = false, length = 50)
    private String status = "completed";

    @Builder.Default
    @Column(nullable = false, columnDefinition = "text")
    private String transcript = "";

    @Builder.Default
    @Column(nullable = false, columnDefinition = "text")
    private String tools = "";

    @Column(columnDefinition = "text")
    private String errorMessage;

    private Long orderId;
}
