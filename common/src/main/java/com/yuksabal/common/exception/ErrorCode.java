package com.yuksabal.common.exception;

import org.springframework.http.HttpStatus;

// 공통·도메인 에러 코드 enum이 구현한다. name()은 enum 상수 이름이 그대로 쓰인다.
public interface ErrorCode {

    String name();

    HttpStatus getStatus();

    String getMessage();
}
