package com.yuksabal.order.order.client;

import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.order.order.client.dto.UserResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.UUID;

// user-service 내부 API 8
@FeignClient(name = "user-service")
public interface UserClient {

    // 회원 조회. 탈퇴 회원 404, 비활성 회원 200 + active=false
    @GetMapping("/internal/users/{userId}")
    ApiResponse<UserResponse> getUser(@PathVariable("userId") UUID userId);
}
