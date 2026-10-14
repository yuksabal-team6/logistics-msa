package com.yuksabal.company.company.service;

import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.company.company.dto.request.CompanyCreateRequest;
import com.yuksabal.company.company.dto.response.CompanyResponse;
import com.yuksabal.company.company.entity.Company;
import com.yuksabal.company.company.repository.CompanyRepository;
import com.yuksabal.company.global.auth.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static com.yuksabal.common.exception.CommonErrorCode.DUPLICATE_RESOURCE;
import static com.yuksabal.common.exception.CommonErrorCode.FORBIDDEN;
import static com.yuksabal.company.global.exception.CompanyErrorCode.COMPANY_ACCESS_DENIED;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CompanyService {

    private final CompanyRepository companyRepository;

    @Transactional
    public CompanyResponse create(CompanyCreateRequest request, UUID userId, UserRole role) {
        // 업체 생성은 MASTER, COMPANY만 가능. 게이트웨이 역할 검사와 별개로 서비스에서도 확인한다
        switch (role) {
            case MASTER -> {
                // 다른 회원을 업체 관리자로 지정할 수 있다
            }
            case COMPANY -> {
                if (!request.ownerId().equals(userId)) {
                    throw new BusinessException(COMPANY_ACCESS_DENIED);
                }
            }
            default -> throw new BusinessException(FORBIDDEN);
        }

        if (request.businessNumber() != null &&
                companyRepository.existsByBusinessNumber(request.businessNumber())) {
            throw new BusinessException(
                    DUPLICATE_RESOURCE,
                    "이미 등록된 사업자 등록번호입니다.",
                    "businessNumber");
        }

        Company saved = companyRepository.save(
                new Company(
                        request.hubId(),
                        request.ownerId(),
                        request.companyName(),
                        request.businessNumber(),
                        request.address(),
                        request.contactPhone()));

        return CompanyResponse.from(saved);
    }
}
