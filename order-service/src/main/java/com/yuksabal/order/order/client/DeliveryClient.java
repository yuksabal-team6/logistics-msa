package com.yuksabal.order.order.client;

import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.order.order.client.dto.DeliveryCancelRequest;
import com.yuksabal.order.order.client.dto.DeliveryCreateRequest;
import com.yuksabal.order.order.client.dto.DeliveryCreateResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

// delivery-service 내부 API #5, #9
@FeignClient(name = "delivery-service")
public interface DeliveryClient {

    // #5 배송 생성. 같은 orderId면 기존 배송 반환 (결정 #18)
    @PostMapping("/internal/deliveries")
    ApiResponse<DeliveryCreateResponse> createDelivery(@RequestBody DeliveryCreateRequest request);

    // #9 배송, 경로 기록 취소 (결정 19). 주문 생성 보상(타임아웃)과 주문 취소에서 호출
    @PostMapping("/internal/deliveries/cancel")
    ApiResponse<Void> cancelDelivery(@RequestBody DeliveryCancelRequest request);
}
