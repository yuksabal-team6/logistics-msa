package com.yuksabal.company.company.service;

import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.response.PageResponse;
import com.yuksabal.company.company.dto.request.CompanyCreateRequest;
import com.yuksabal.company.company.dto.request.CompanySearchRequest;
import com.yuksabal.company.company.dto.request.CompanyUpdateRequest;
import com.yuksabal.company.company.dto.response.CompanyResponse;
import com.yuksabal.company.company.entity.Company;
import com.yuksabal.company.company.repository.CompanyRepository;
import com.yuksabal.company.global.auth.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

import static com.yuksabal.common.exception.CommonErrorCode.DUPLICATE_RESOURCE;
import static com.yuksabal.common.exception.CommonErrorCode.FORBIDDEN;
import static com.yuksabal.common.exception.CommonErrorCode.RESOURCE_NOT_FOUND;
import static com.yuksabal.common.exception.CommonErrorCode.VALIDATION_ERROR;
import static com.yuksabal.company.company.repository.CompanySpecification.companyNameContains;
import static com.yuksabal.company.company.repository.CompanySpecification.hubIdEquals;
import static com.yuksabal.company.global.exception.CompanyErrorCode.COMPANY_ACCESS_DENIED;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CompanyService {

    // 목록 정렬 허용 필드 (API 명세 0.4: 정렬 필드는 API별로 제한)
    private static final Set<String> SORTABLE_FIELDS = Set.of("createdAt", "companyName");

    private final CompanyRepository companyRepository;

    @Transactional
    public CompanyResponse createCompany(CompanyCreateRequest request, UUID userId, UserRole role) {
        validateOwnerPermission(request.ownerId(), userId, role);
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

    public PageResponse<CompanyResponse> getCompanies(CompanySearchRequest request, Pageable pageable) {
        Specification<Company> spec = Specification
                .where(companyNameContains(request.companyName()))
                .and(hubIdEquals(request.hubId()));

        Page<Company> companies = companyRepository.findAll(spec, toStablePageable(pageable));

        return PageResponse.from(companies, CompanyResponse::from);
    }

    @Transactional
    public CompanyResponse updateCompany(UUID companyId, CompanyUpdateRequest request, UUID userId, UserRole role) {
        Company company = findCompany(companyId);
        validateOwnerPermission(company.getOwnerId(), userId, role);

        if (role != UserRole.MASTER &&
                (isChanged(request.hubId(), company.getHubId()) ||
                        isChanged(request.ownerId(), company.getOwnerId()))) {
            throw new BusinessException(FORBIDDEN);
        }
        if (isChanged(request.businessNumber(), company.getBusinessNumber())) {
            validateDuplicateBusinessNumber(request.businessNumber());
        }

        company.updateInfo(request.companyName(), request.address(),
                request.contactPhone(), request.businessNumber());
        if (isChanged(request.hubId(), company.getHubId())) {
            // TODO #7: 허브 존재 확인 (hub 내부 API 준비 후)
            company.changeHub(request.hubId());
        }
        if (isChanged(request.ownerId(), company.getOwnerId())) {
            // TODO #8: 새 관리자 소속 확인 (user 내부 API 준비 후)
            company.changeOwner(request.ownerId());
        }

        // 변경 감지는 커밋 때 UPDATE하므로, 응답의 updatedAt을 채우려고 먼저 반영한다
        companyRepository.flush();
        return CompanyResponse.from(company);
    }

    // 업체 삭제는 MASTER만 가능. Soft Delete라 실제로는 deleted_at·deleted_by를 기록하는 UPDATE
    @Transactional
    public void deleteCompany(UUID companyId, UUID userId, UserRole role) {
        if (role != UserRole.MASTER) {
            throw new BusinessException(FORBIDDEN);
        }

        Company company = findCompany(companyId);
        // TODO 상품 이슈: 업체의 상품도 함께 Soft Delete
        company.delete(userId);
    }

    // 삭제된 업체는 @SQLRestriction으로 조회되지 않아 없는 업체와 같이 404. 조회·수정·삭제 공용
    private Company findCompany(UUID companyId) {
        return companyRepository.findById(companyId)
                .orElseThrow(() -> new BusinessException(
                        RESOURCE_NOT_FOUND,
                        "존재하지 않거나 삭제된 업체입니다.",
                        "companyId"));
    }

    // 허용하지 않은 정렬 필드는 400. 정렬 값이 같은 행의 순서가 페이지마다 바뀌지 않게 id를 마지막 기준으로 붙인다
    private Pageable toStablePageable(Pageable pageable) {
        for (Sort.Order order : pageable.getSort()) {
            if (!SORTABLE_FIELDS.contains(order.getProperty())) {
                throw new BusinessException(
                        VALIDATION_ERROR,
                        "정렬할 수 없는 필드입니다: " + order.getProperty(),
                        "sort");
            }
        }
        return PageRequest.of(
                pageable.getPageNumber(),
                pageable.getPageSize(),
                pageable.getSort().and(Sort.by("id")));
    }

    // 값을 보냈고(null 아님) 기존 값과 다를 때만 "바뀜"으로 본다
    private <T> boolean isChanged(T newValue, T currentValue) {
        return newValue != null && !newValue.equals(currentValue);
    }

    // MASTER 또는 업체 관리자 본인만 허용. 게이트웨이 역할 검사와 별개로 서비스에서도 확인한다
    private void validateOwnerPermission(UUID ownerId, UUID userId, UserRole role) {
        switch (role) {
            case MASTER -> {
                // 모든 업체를 관리할 수 있다
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
