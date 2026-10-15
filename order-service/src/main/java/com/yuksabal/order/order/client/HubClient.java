package com.yuksabal.order.order.client;

import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.order.order.client.dto.StockDecreaseResponse;
import com.yuksabal.order.order.client.dto.StockRequest;
import com.yuksabal.order.order.client.dto.StockRestoreResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

// hub-service 내부 API #3, #4. 같은 (orderId, 유형)이면 변경 없이 성공 (결정 #18)
@FeignClient(name = "hub-service")
public interface HubClient {

    // #3 재고 차감. 409 INSUFFICIENT_STOCK → 주문 FAILED + NOT_REQUIRED
    @PostMapping("/internal/stocks/decrease")
    ApiResponse<StockDecreaseResponse> decreaseStock(@RequestBody StockRequest request);

    // #4 재고 복원 (보상). 같은 orderId의 차감 기록이 있을 때만 복원
    @PostMapping("/internal/stocks/restore")
    ApiResponse<StockRestoreResponse> restoreStock(@RequestBody StockRequest request);
}
