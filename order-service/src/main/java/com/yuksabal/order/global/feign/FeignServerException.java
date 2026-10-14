package com.yuksabal.order.global.feign;

import lombok.Getter;

// 다른 서비스의 5xx 응답. 재시도 또는 보상 처리 대상이다.
// 보상 처리 시 타임아웃(feign.RetryableException)도 함께 잡는다: catch (FeignServerException | RetryableException e)
@Getter
public class FeignServerException extends RuntimeException {

    private final int status;
    private final String code;

    public FeignServerException(int status, String code, String message, String methodKey) {
        super(methodKey + " 호출 실패 (" + status + "): " + message);
        this.status = status;
        this.code = code;
    }
}
