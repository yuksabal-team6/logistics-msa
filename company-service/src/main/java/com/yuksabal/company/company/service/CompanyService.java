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
import static com.yuksabal.common.exception.CommonErrorCode.RESOURCE_NOT_FOUND;
import static com.yuksabal.company.global.exception.CompanyErrorCode.COMPANY_ACCESS_DENIED;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CompanyService {

    private final CompanyRepository companyRepository;

    @Transactional
    public CompanyResponse createCompany(CompanyCreateRequest request, UUID userId, UserRole role) {
        validateCreatePermission(request.ownerId(), userId, role);
        validateDuplicateBusinessNumber(request.businessNumber());

        Company company = Company.builder()
                .hubId(request.hubId())
                .ownerId(request.ownerId())
                .companyName(request.companyName())
                .businessNumber(request.businessNumber())
                .address(request.address())
                .contactPhone(request.contactPhone())
                .build();

        return CompanyResponse.from(companyRepository.save(company));
    }

    public CompanyResponse getCompany(UUID companyId) {
        return CompanyResponse.from(findCompany(companyId));
    }

    // 삭제된 업체는 @SQLRestriction으로 조회되지 않아 없는 업체와 같이 404. 조회·수정·삭제 공용
    private Company findCompany(UUID companyId) {
        return companyRepository.findById(companyId)
                .orElseThrow(() -> new BusinessException(
                        RESOURCE_NOT_FOUND,
                        "존재하지 않거나 삭제된 업체입니다.",
                        "companyId"));
    }

    // 업체 생성은 MASTER, COMPANY만 가능. 게이트웨이 역할 검사와 별개로 서비스에서도 확인한다
    private void validateCreatePermission(UUID ownerId, UUID userId, UserRole role) {
        switch (role) {
            case MASTER -> {
                // 다른 회원을 업체 관리자로 지정할 수 있다
            }
            case COMPANY -> {
                if (!ownerId.equals(userId)) {
                    throw new BusinessException(COMPANY_ACCESS_DENIED);
                }
            }
            default -> throw new BusinessException(FORBIDDEN);
        }
    }

    // 선택 입력이라 null이면 검사하지 않는다 (null로 조회하면 IS NULL 조건이 되어 미입력 업체끼리 중복으로 오판)
    // 동시 요청이 함께 통과하면 DB 부분 유니크 인덱스가 막고 common 핸들러가 409로 응답한다
    private void validateDuplicateBusinessNumber(String businessNumber) {
        if (businessNumber != null &&
                companyRepository.existsByBusinessNumber(businessNumber)) {
            throw new BusinessException(
                    DUPLICATE_RESOURCE,
                    "이미 등록된 사업자 등록번호입니다.",
                    "businessNumber");
        }
    }
}
