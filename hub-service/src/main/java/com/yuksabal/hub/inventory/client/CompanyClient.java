package com.yuksabal.hub.inventory.client;

import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.hub.inventory.client.dto.CompanyResponse;
import com.yuksabal.hub.inventory.client.dto.ProductResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.UUID;

// company-service 내부 API
@FeignClient(name = "company-service")
public interface CompanyClient {

    // #1 상품 조회. 없거나 삭제된 상품은 404
    @GetMapping("/internal/products/{productId}")
    ApiResponse<ProductResponse> getProduct(@PathVariable("productId") UUID productId);

    // #2 업체 조회. 없거나 삭제된 업체는 404
    @GetMapping("/internal/companies/{companyId}")
    ApiResponse<CompanyResponse> getCompany(@PathVariable("companyId") UUID companyId);
}
