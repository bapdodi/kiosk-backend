package com.example.demo.service.channel;

import org.springframework.http.MediaType;

/** 판매채널에 업로드할 이미지 1장(바이트 + 파일명 + MIME). 채널 무관. */
public record ImagePart(byte[] bytes, String filename, MediaType mediaType) {
}
