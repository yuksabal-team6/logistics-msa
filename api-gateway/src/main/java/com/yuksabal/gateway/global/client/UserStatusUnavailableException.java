package com.yuksabal.gateway.global.client;

// user-service 회원 조회 실패 (연결 실패·타임아웃·5xx·응답 형식 오류). 인증 필터가 503으로 차단한다 (fail-closed)
public class UserStatusUnavailableException extends RuntimeException {

    public UserStatusUnavailableException(String message) {
        super(message);
    }

    public UserStatusUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
