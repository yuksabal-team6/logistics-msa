package com.yuksabal.user.user.client;

import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.user.user.client.dto.CompanyResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.UUID;

// company-service 내부 API #2, #11
@FeignClient(name = "company-service")
public interface CompanyClient {

    // #2 업체 조회. 없거나 삭제된 업체는 404 → FeignClientException
    @GetMapping("/internal/companies/{companyId}")
    ApiResponse<CompanyResponse> getCompany(@PathVariable("companyId") UUID companyId);

    // #11 회원이 관리하는 업체(owner_id = 회원) 목록. 없으면 빈 목록, 삭제된 업체는 제외
    // 1인 1업체 규칙상 0개 또는 1개지만, 규칙 이전 데이터를 고려해 목록으로 받는다
    @GetMapping("/internal/companies")
    ApiResponse<List<CompanyResponse>> getCompaniesByOwner(@RequestParam("ownerId") UUID ownerId);
}
