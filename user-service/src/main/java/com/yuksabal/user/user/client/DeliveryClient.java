package com.yuksabal.user.user.client;

import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.user.user.client.dto.DeliveryInProgressResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.UUID;

// delivery-service 내부 API (#117)
@FeignClient(name = "delivery-service")
public interface DeliveryClient {

    // 회원(배송 담당자)의 진행 중 배송 존재 여부. 진행 중 = 배송 READY~DELIVERING, 경로 기록 READY·IN_TRANSIT (delivery 기준)
    @GetMapping("/internal/deliveries/in-progress")
    ApiResponse<DeliveryInProgressResponse> getInProgressDelivery(@RequestParam("deliveryManagerId") UUID deliveryManagerId);
}
