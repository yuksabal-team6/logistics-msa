package com.yuksabal.user.user.client;

import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.user.user.client.dto.HubResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.UUID;

// hub-service 내부 API #7
@FeignClient(name = "hub-service")
public interface HubClient {

    // #7 허브 존재 확인. 없거나 삭제된 허브는 404 → FeignClientException
    @GetMapping("/internal/hubs/{hubId}")
    ApiResponse<HubResponse> getHub(@PathVariable("hubId") UUID hubId);
}
