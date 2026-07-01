package com.example.demo.service.channel;

/** 판매채널(네이버/쿠팡 등) 연동 실패를 나타내는 런타임 예외. */
public class ChannelApiException extends RuntimeException {
    public ChannelApiException(String message) {
        super(message);
    }

    public ChannelApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
