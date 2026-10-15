package com.yuksabal.user.global.exception;

import com.yuksabal.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

// user-service 도메인 에러. 공통 에러는 CommonErrorCode를 사용한다.
@Getter
@RequiredArgsConstructor
public enum UserErrorCode implements ErrorCode {
    // 아이디가 없는지 비밀번호가 틀렸는지 구분하지 않는다 (가입된 아이디 노출 방지)
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호가 올바르지 않습니다."),
    // 비밀번호 변경 시 현재 비밀번호 불일치. 토큰 오류(UNAUTHORIZED·TOKEN_EXPIRED)와 코드로 구분된다
    INVALID_PASSWORD(HttpStatus.UNAUTHORIZED, "현재 비밀번호가 일치하지 않습니다.");

    private final HttpStatus status;
    private final String message;
}
