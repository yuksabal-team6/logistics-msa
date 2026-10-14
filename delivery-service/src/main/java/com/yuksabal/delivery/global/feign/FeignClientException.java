package com.yuksabal.delivery.global.feign;

import lombok.Getter;

// 다른 서비스가 4xx로 거절한 요청 (재고 부족, 대상 없음 등). 재시도하지 않는다.
@Getter
public class FeignClientException extends RuntimeException {

    private final int status;
    private final String code;

    public FeignClientException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
}
