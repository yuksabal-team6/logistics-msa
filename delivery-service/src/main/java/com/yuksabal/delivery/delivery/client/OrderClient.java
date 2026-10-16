package com.yuksabal.delivery.delivery.client;

import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.delivery.delivery.client.dto.OrderCompleteResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.UUID;

@FeignClient(name = "order-service")
public interface OrderClient {

    // 내부 API #10: 배송 완료 → 주문 COMPLETED. 이미 COMPLETED면 변경 없이 200
    // 주문 없음 404, CONFIRMED가 아니면 409 → FeignClientException
    @PostMapping("/internal/orders/{orderId}/complete")
    ApiResponse<OrderCompleteResponse> completeOrder(@PathVariable("orderId") UUID orderId);
}