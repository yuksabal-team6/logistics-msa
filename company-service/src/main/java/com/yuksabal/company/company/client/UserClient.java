package com.yuksabal.company.company.client;

import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.company.company.client.dto.response.UserResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.UUID;

// user-service 내부 API. 구현체는 Spring이 실행 시 만든다 (Eureka 서비스 이름으로 호출)
@FeignClient(name = "user-service")
public interface UserClient {

    // 내부 API #8 회원 조회. 없거나 탈퇴한 회원은 404
    @GetMapping("/internal/users/{userId}")
    ApiResponse<UserResponse> getUser(@PathVariable("userId") UUID userId);
}
