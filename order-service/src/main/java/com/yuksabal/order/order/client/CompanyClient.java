package com.yuksabal.order.order.client;

import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.order.order.client.dto.CompanyResponse;
import com.yuksabal.order.order.client.dto.ProductResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.UUID;

// company-service 내부 API #1, #2
@FeignClient(name = "company-service")
public interface CompanyClient {

    // #1 상품 조회. 404 → 주문 실패
    @GetMapping("/internal/products/{productId}")
    ApiResponse<ProductResponse> getProduct(@PathVariable("productId") UUID productId);

    // #2 업체 조회 (공급 업체·요청 업체). 404 → 주문 실패
    @GetMapping("/internal/companies/{companyId}")
    ApiResponse<CompanyResponse> getCompany(@PathVariable("companyId") UUID companyId);
}
