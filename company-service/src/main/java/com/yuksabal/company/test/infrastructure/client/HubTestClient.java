package com.yuksabal.company.test.infrastructure.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.UUID;

// [세팅 검증용] hub-service 내부 API 호출 (Eureka에서 hub-service를 찾아 호출)
@FeignClient(name = "hub-service", contextId = "hubTestClient")
public interface HubTestClient {

    @GetMapping("/internal/test/hubs/{hubId}")
    HubTestResponse getHub(@PathVariable("hubId") UUID hubId);

    record HubTestResponse(UUID hubId, String hubName) {
    }
}
