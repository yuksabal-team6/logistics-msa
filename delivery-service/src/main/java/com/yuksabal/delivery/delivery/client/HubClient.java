package com.yuksabal.delivery.delivery.client;

import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.delivery.delivery.client.dto.HubRouteResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.UUID;

@FeignClient(name = "hub-service")
public interface HubClient {

    // 내부 API #6: 두 허브 사이 P2P 경로 조회
    // 경로 없으면 404 → FeignClientException
    @GetMapping("/internal/hub-routes")
    ApiResponse<HubRouteResponse> getHubRoute(@RequestParam("from") UUID from,
                                              @RequestParam("to") UUID to);
}
