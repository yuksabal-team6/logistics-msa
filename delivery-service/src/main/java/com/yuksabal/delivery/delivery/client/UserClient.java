package com.yuksabal.delivery.delivery.client;

import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.delivery.delivery.client.dto.UserResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.UUID;

// 배송·경로 기록 담당자 지정에서 같이 쓴다 (routelog도 이 클라이언트를 사용)
@FeignClient(name = "user-service")
public interface UserClient {

    // 내부 API #8 회원 조회. 없거나 탈퇴한 회원은 404 → FeignClientException
    @GetMapping("/internal/users/{userId}")
    ApiResponse<UserResponse> getUser(@PathVariable("userId") UUID userId);
}