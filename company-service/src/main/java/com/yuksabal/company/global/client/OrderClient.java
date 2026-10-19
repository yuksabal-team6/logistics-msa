package com.yuksabal.company.global.client;

import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.company.global.client.dto.response.OrderInProgressResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.UUID;

// order-service 내부 API. 구현체는 Spring이 실행 시 만든다 (Eureka 서비스 이름으로 호출)
// 계약 (#143에서 order 담당자와 합의, order 구현 전): 회원 기준 /internal/orders/in-progress?ordererId= 에 상품·업체 기준 파라미터를 추가
@FeignClient(name = "order-service")
public interface OrderClient {

    // 이 상품의 진행 중 주문이 있는지 (상품 삭제 전 확인)
    @GetMapping("/internal/orders/in-progress")
    ApiResponse<OrderInProgressResponse> getInProgressByProduct(@RequestParam("productId") UUID productId);

    // 이 업체가 공급 업체나 요청 업체로 걸린 진행 중 주문이 있는지 (업체 삭제 전 확인)
    @GetMapping("/internal/orders/in-progress")
    ApiResponse<OrderInProgressResponse> getInProgressByCompany(@RequestParam("companyId") UUID companyId);
}
