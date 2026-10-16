package com.yuksabal.delivery.global.exception;

import com.yuksabal.common.response.ApiResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

// delivery-service 전용 예외 처리. 여기서 처리하지 않는 예외는 common GlobalExceptionHandler가 처리한다.
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class DeliveryExceptionHandler {

    // @Version 충돌. 커밋 시점에 발생해 서비스 안에서 잡을 수 없으므로 여기서 409로 변환한다
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleOptimisticLock(OptimisticLockingFailureException e) {
        DeliveryErrorCode errorCode = DeliveryErrorCode.DELIVERY_CONFLICT;
        return ResponseEntity
                .status(errorCode.getStatus())
                .body(ApiResponse.fail(errorCode));
    }
}
