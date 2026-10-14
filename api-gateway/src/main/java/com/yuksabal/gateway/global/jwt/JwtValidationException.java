package com.yuksabal.gateway.global.jwt;

import com.yuksabal.gateway.global.exception.GatewayErrorCode;
import lombok.Getter;

// 토큰 검증 실패. 메시지에 토큰 내용을 넣지 않는다.
@Getter
public class JwtValidationException extends RuntimeException {

    private final GatewayErrorCode errorCode;

    public JwtValidationException(GatewayErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }
}
