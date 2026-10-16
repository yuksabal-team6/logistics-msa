package com.yuksabal.user.user.client;

import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.user.user.client.dto.CompanyResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.UUID;

// company-service 내부 API #2
@FeignClient(name = "company-service")
public interface CompanyClient {

    // #2 업체 조회. 없거나 삭제된 업체는 404 → FeignClientException
    @GetMapping("/internal/companies/{companyId}")
    ApiResponse<CompanyResponse> getCompany(@PathVariable("companyId") UUID companyId);
}
