package com.yuksabal.user.user.client;

import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.user.user.client.dto.OrderInProgressResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.UUID;

// order-service 내부 API (#96)
@FeignClient(name = "order-service")
public interface OrderClient {

    // 회원(주문자)의 진행 중 주문 존재 여부. 진행 중 = PENDING, CONFIRMED, 보상 미완료 (order 기준)
    @GetMapping("/internal/orders/in-progress")
    ApiResponse<OrderInProgressResponse> getInProgressOrder(@RequestParam("ordererId") UUID ordererId);
}
