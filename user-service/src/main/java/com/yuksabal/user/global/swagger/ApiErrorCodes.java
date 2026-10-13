package com.yuksabal.user.global.swagger;

import com.yuksabal.common.exception.CommonErrorCode;
import com.yuksabal.user.global.exception.UserErrorCode;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// 컨트롤러 메서드에서 발생할 수 있는 에러를 Swagger 응답 예시로 노출한다.
// 예: @ApiErrorCodes(common = {INVALID_INPUT, UNAUTHORIZED}, value = {...})
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ApiErrorCodes {

    CommonErrorCode[] common() default {};

    UserErrorCode[] value() default {};
}
