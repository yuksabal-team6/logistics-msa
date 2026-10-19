package com.yuksabal.company.global.client;

import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.company.global.client.dto.response.HubResponse;
import com.yuksabal.company.global.client.dto.response.InventoryStockResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.UUID;

// hub-service 내부 API. 구현체는 Spring이 실행 시 만든다 (Eureka 서비스 이름으로 호출)
@FeignClient(name = "hub-service")
public interface HubClient {

    // 내부 API #7 허브 존재 확인. 없으면 404
    @GetMapping("/internal/hubs/{hubId}")
    ApiResponse<HubResponse> getHub(@PathVariable("hubId") UUID hubId);

    // hub 재고 확인 내부 API (#146): 이 상품의 허브 재고가 남아 있는지 (상품 삭제 전 확인)
    @GetMapping("/internal/inventories/stock")
    ApiResponse<InventoryStockResponse> getStockByProduct(@RequestParam("productId") UUID productId);

    // hub 재고 확인 내부 API (#146): 이 업체 상품들의 허브 재고가 하나라도 남아 있는지 (업체 삭제 전 확인)
    @GetMapping("/internal/inventories/stock")
    ApiResponse<InventoryStockResponse> getStockByCompany(@RequestParam("companyId") UUID companyId);
}
