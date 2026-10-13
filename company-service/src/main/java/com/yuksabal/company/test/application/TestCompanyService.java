package com.yuksabal.company.test.application;

import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.exception.CommonErrorCode;
import com.yuksabal.company.company.domain.Company;
import com.yuksabal.company.company.domain.CompanyRepository;
import com.yuksabal.company.test.infrastructure.client.HubTestClient;
import com.yuksabal.company.test.infrastructure.client.HubTestClient.HubTestResponse;
import com.yuksabal.company.test.presentation.dto.TestCompanyCreateRequest;
import com.yuksabal.company.test.presentation.dto.TestCompanyResponse;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

// [세팅 검증용]
@Service
@RequiredArgsConstructor
public class TestCompanyService {

    private final CompanyRepository companyRepository;
    private final HubTestClient hubTestClient;

    @Transactional
    public TestCompanyResponse create(TestCompanyCreateRequest request) {
        HubTestResponse hub = getHub(request.hubId());

        Company company = Company.builder()
                .hubId(request.hubId())
                .ownerId(request.ownerId())
                .companyName(request.companyName())
                .address(request.address())
                .build();
        // 응답에 created_at 등이 바로 보이도록 즉시 flush
        companyRepository.saveAndFlush(company);
        return TestCompanyResponse.of(company, hub.hubName());
    }

    @Transactional(readOnly = true)
    public TestCompanyResponse get(UUID companyId) {
        Company company = findCompany(companyId);
        return TestCompanyResponse.of(company, getHub(company.getHubId()).hubName());
    }

    @Transactional
    public TestCompanyResponse delete(UUID companyId, UUID userId) {
        Company company = findCompany(companyId);
        company.delete(userId);
        companyRepository.flush();
        return TestCompanyResponse.of(company, null);
    }

    private Company findCompany(UUID companyId) {
        return companyRepository.findById(companyId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
    }

    // 허브가 없으면 hub-service가 404 → 공통 NOT_FOUND로 변환
    private HubTestResponse getHub(UUID hubId) {
        try {
            return hubTestClient.getHub(hubId);
        } catch (FeignException.NotFound e) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND);
        }
    }
}
