package com.yuksabal.user.global.exception;

import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.exception.CommonErrorCode;
import com.yuksabal.common.exception.ErrorCode;
import com.yuksabal.common.exception.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException e) {
        return toResponse(e.getErrorCode());
    }

    // @Valid 검증 실패 시 첫 번째 필드 에러 메시지로 응답
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException e) {
        ErrorCode errorCode = CommonErrorCode.INVALID_INPUT;
        FieldError fieldError = e.getBindingResult().getFieldError();
        String message = fieldError != null ? fieldError.getDefaultMessage() : errorCode.getMessage();
        return ResponseEntity
                .status(errorCode.getStatus())
                .body(ErrorResponse.of(errorCode, message));
    }

    // JSON 형식 오류, 경로/쿼리 파라미터 타입 불일치
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> handleBadRequest(Exception e) {
        return toResponse(CommonErrorCode.INVALID_INPUT);
    }

    // 존재하지 않는 URL
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException e) {
        return toResponse(CommonErrorCode.NOT_FOUND);
    }

    // 지원하지 않는 HTTP 메서드
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return toResponse(CommonErrorCode.METHOD_NOT_ALLOWED);
    }

    // 처리되지 않은 모든 예외
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception e) {
        log.error("Unhandled exception", e);
        return toResponse(CommonErrorCode.INTERNAL_SERVER_ERROR);
    }

    private ResponseEntity<ErrorResponse> toResponse(ErrorCode errorCode) {
        return ResponseEntity
                .status(errorCode.getStatus())
                .body(ErrorResponse.from(errorCode));
    }
}
