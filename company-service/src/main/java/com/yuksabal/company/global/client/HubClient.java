package com.yuksabal.company.global.client;

import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.company.global.client.dto.response.HubResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.UUID;

// hub-service 내부 API. 구현체는 Spring이 실행 시 만든다 (Eureka 서비스 이름으로 호출)
@FeignClient(name = "hub-service")
public interface HubClient {

    // 내부 API #7 허브 존재 확인. 없으면 404
    @GetMapping("/internal/hubs/{hubId}")
    ApiResponse<HubResponse> getHub(@PathVariable("hubId") UUID hubId);
}
