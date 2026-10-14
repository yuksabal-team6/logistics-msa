package com.yuksabal.common.feign;

import com.yuksabal.common.response.ApiErrorDetail;
import lombok.Getter;

import java.util.List;

// 다른 서비스가 4xx로 거절한 요청 (재고 부족, 대상 없음 등). 재시도하지 않는다.
@Getter
public class FeignClientException extends RuntimeException {

    private final int status;
    private final String code;
    private final List<ApiErrorDetail> details;

    public FeignClientException(int status, String code, String message, List<ApiErrorDetail> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }
}
