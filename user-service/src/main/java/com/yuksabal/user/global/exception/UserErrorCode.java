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
    INVALID_PASSWORD(HttpStatus.UNAUTHORIZED, "현재 비밀번호가 일치하지 않습니다."),
    // 비활성화된 계정의 로그인·API 요청 (#67). 비밀번호가 맞을 때만 이 코드로 알려준다
    ACCOUNT_DISABLED(HttpStatus.FORBIDDEN, "비활성화된 계정입니다."),
    // Refresh Token 없음·만료·이미 사용됨(rotation)·탈퇴 회원. 구분하지 않고 다시 로그인하게 한다
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않은 Refresh Token입니다. 다시 로그인해주세요."),
    // 회원 삭제·비활성화·DELIVERY 역할 변경 시 진행 중인 업무가 있음 (#68). 어떤 업무인지는 details로 알려준다
    // 실제 응답 메시지는 작업별로 다르다 (예: "진행 중인 업무가 있어 회원을 삭제할 수 없습니다.")
    USER_HAS_ACTIVE_WORK(HttpStatus.CONFLICT, "진행 중인 업무가 있어 처리할 수 없습니다.");

    private final HttpStatus status;
    private final String message;
}
