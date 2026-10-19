package com.yuksabal.company.company.service;

import com.yuksabal.common.auth.UserRole;
import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.feign.FeignClientException;
import com.yuksabal.common.response.PageResponse;
import com.yuksabal.company.company.client.HubClient;
import com.yuksabal.company.company.client.UserClient;
import com.yuksabal.company.company.client.dto.response.UserResponse;
import com.yuksabal.company.company.dto.request.CompanyCreateRequest;
import com.yuksabal.company.company.dto.request.CompanySearchRequest;
import com.yuksabal.company.company.dto.request.CompanyStatusRequest;
import com.yuksabal.company.company.dto.request.CompanyUpdateRequest;
import com.yuksabal.company.company.dto.response.CompanyInternalResponse;
import com.yuksabal.company.company.dto.response.CompanyResponse;
import com.yuksabal.company.company.entity.Company;
import com.yuksabal.company.company.repository.CompanyRepository;
import com.yuksabal.company.global.auth.OwnerPermission;
import com.yuksabal.company.product.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.yuksabal.common.exception.CommonErrorCode.BUSINESS_RULE_VIOLATION;
import static com.yuksabal.common.exception.CommonErrorCode.DUPLICATE_RESOURCE;
import static com.yuksabal.common.exception.CommonErrorCode.FORBIDDEN;
import static com.yuksabal.common.exception.CommonErrorCode.RESOURCE_NOT_FOUND;
import static com.yuksabal.common.util.PageableUtils.toStablePageable;
import static com.yuksabal.company.company.repository.CompanySpecification.activeEquals;
import static com.yuksabal.company.company.repository.CompanySpecification.companyNameContains;
import static com.yuksabal.company.company.repository.CompanySpecification.hubIdEquals;

// 검증 메서드 이름 규칙: validate* = 로컬 검사, verify* = 외부 서비스 확인, fetch* = 외부 서비스 조회 (외부는 502 가능)
// 생성·수정은 권한 → 로컬 → 외부 순서로 검증해, 실패할 요청을 싼 검사에서 먼저 거른다
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CompanyService {

    // 목록 정렬 허용 필드 (API 명세 0.4: 정렬 필드는 API별로 제한)
    private static final Set<String> SORTABLE_FIELDS = Set.of("createdAt", "companyName");

    private final CompanyRepository companyRepository;
    private final ProductService productService;
    private final UserClient userClient;
    private final HubClient hubClient;

    @Transactional
    public CompanyResponse createCompany(CompanyCreateRequest request, UUID userId, UserRole role) {
        // 1. 권한: MASTER는 누구든 관리자로 지정, COMPANY는 본인만 (403)
        OwnerPermission.validateOwner(request.ownerId(), userId, role);

        // 2. 로컬 검사: 사업자 등록번호 중복, 관리자가 이미 다른 업체를 관리 중 (409)
        validateDuplicateBusinessNumber(request.businessNumber());
        validateDuplicateOwner(request.ownerId());

        // 3. 외부 확인: 허브 존재(hub, 404) → 관리자 자격(user, 회원 없음 404 / 비활성·COMPANY 아님 422). 장애는 502
        verifyHubExists(request.hubId());
        verifyOwner(request.ownerId(), null);   // 업체가 아직 없어 소속 확인 생략

        // 4. 반영
        Company company = Company.builder()
                .hubId(request.hubId())
                .ownerId(request.ownerId())
                .companyName(request.companyName())
                .businessNumber(request.businessNumber())
                .address(request.address())
                .contactPhone(request.contactPhone())
                .build();

        // TODO 관리자 소속(user company_id) 자동 지정 검토 중. 현재는 업체 생성 후 MASTER가 회원 수정으로 지정
        return CompanyResponse.from(companyRepository.save(company));
    }

    public CompanyResponse getCompany(UUID companyId) {
        return CompanyResponse.from(findCompany(companyId));
    }

    // 내부 API #2 전용. 외부 단건 조회와 같은 404 규칙, 응답만 내부 계약(CompanyInternalResponse)
    public CompanyInternalResponse getCompanyForInternal(UUID companyId) {
        return CompanyInternalResponse.from(findCompany(companyId));
    }

    // 내부 API #11 전용. 관리 중인 업체가 없으면 빈 목록 (회원 삭제·역할 변경 확인용이라 "없음"은 정상 결과)
    public List<CompanyInternalResponse> getCompaniesByOwnerForInternal(UUID ownerId) {
        return companyRepository.findAllByOwnerId(ownerId).stream()
                .map(CompanyInternalResponse::from)
                .toList();
    }

    public PageResponse<CompanyResponse> getCompanies(CompanySearchRequest request, Pageable pageable) {
        Specification<Company> spec = Specification
                .where(companyNameContains(request.companyName()))
                .and(hubIdEquals(request.hubId()))
                .and(activeEquals(request.active()));

        Page<Company> companies = companyRepository.findAll(spec, toStablePageable(pageable, SORTABLE_FIELDS));

        return PageResponse.from(companies, CompanyResponse::from);
    }

    @Transactional
    public CompanyResponse updateCompany(UUID companyId, CompanyUpdateRequest request, UUID userId, UserRole role) {
        // 1-1. 권한: 업체 조회(404) 후 MASTER 또는 이 업체의 관리자 본인 (403)
        Company company = findCompany(companyId);
        OwnerPermission.validateOwner(company.getOwnerId(), userId, role);

        // 변경 여부는 값을 바꾸기 전에 한 번만 판단해 검증과 반영에 같이 쓴다
        boolean businessNumberChanged = isChanged(request.businessNumber(), company.getBusinessNumber());
        boolean hubChanged = isChanged(request.hubId(), company.getHubId());
        boolean ownerChanged = isChanged(request.ownerId(), company.getOwnerId());

        // 1-2. 권한: 관리 허브·관리자 변경은 MASTER만 가능 (403)
        if (role != UserRole.MASTER && (hubChanged || ownerChanged)) {
            throw new BusinessException(FORBIDDEN);
        }

        // 2. 로컬 검사: 바뀌는 값만 중복 확인 (409)
        if (businessNumberChanged) {
            validateDuplicateBusinessNumber(request.businessNumber());
        }
        if (ownerChanged) {
            validateDuplicateOwner(request.ownerId());
        }

        // 3. 외부 확인: 바뀌는 값만 hub·user에 확인 (404, 422, 장애 502)
        if (hubChanged) {
            verifyHubExists(request.hubId());
        }
        if (ownerChanged) {
            verifyOwner(request.ownerId(), companyId);   // 새 관리자가 이 업체 소속이어야 함
        }

        // 4. 반영
        company.updateInfo(request.companyName(), request.address(),
                request.contactPhone(), request.businessNumber());
        if (hubChanged) {
            company.changeHub(request.hubId());
        }
        if (ownerChanged) {
            company.changeOwner(request.ownerId());
        }

        // 변경 감지는 커밋 때 UPDATE하므로, 응답의 updatedAt을 채우려고 먼저 반영한다
        companyRepository.flush();
        return CompanyResponse.from(company);
    }

    // 활성화·비활성화는 MASTER만 (삭제와 같은 권한). 이미 같은 상태면 바꾸지 않고 200
    // 비활성화하면 소속 상품도 함께 비활성화한다 → "활성 상품은 항상 활성 업체에 속한다"
    // 새 주문·입고 차단은 order·hub가 #1·#2의 active로 판단하고, 진행 중인 주문·배송은 그대로 진행된다
    @Transactional
    public CompanyResponse changeCompanyStatus(UUID companyId, CompanyStatusRequest request, UserRole role) {
        if (role != UserRole.MASTER) {
            throw new BusinessException(FORBIDDEN);
        }

        Company company = findCompany(companyId);
        if (company.isActive() == request.active()) {
            return CompanyResponse.from(company);
        }

        if (!request.active()) {
            productService.deactivateAllByCompany(companyId);
        }
        company.changeActive(request.active());

        // 변경 감지는 커밋 때 UPDATE하므로, 응답의 updatedAt을 채우려고 먼저 반영한다
        companyRepository.flush();
        return CompanyResponse.from(company);
    }

    // 업체 삭제는 MASTER만 가능. 소속 상품도 같은 트랜잭션에서 함께 Soft Delete(하나라도 실패하면 모두 롤백)
    @Transactional
    public void deleteCompany(UUID companyId, UUID userId, UserRole role) {
        if (role != UserRole.MASTER) {
            throw new BusinessException(FORBIDDEN);
        }

        Company company = findCompany(companyId);
        productService.deleteAllByCompany(companyId, userId);
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

    // 값을 보냈고(null 아님) 기존 값과 다를 때만 "바뀜"으로 본다
    private <T> boolean isChanged(T newValue, T currentValue) {
        return newValue != null && !newValue.equals(currentValue);
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

    // 한 회원은 한 업체만 관리한다. 동시 요청이 함께 통과하면 DB 부분 유니크 인덱스가 막고 common 핸들러가 409로 응답한다
    private void validateDuplicateOwner(UUID ownerId) {
        if (companyRepository.existsByOwnerId(ownerId)) {
            throw new BusinessException(
                    DUPLICATE_RESOURCE,
                    "이미 다른 업체를 관리하는 회원입니다.",
                    "ownerId");
        }
    }

    // 허브가 없으면 hub가 404로 응답하고 common Feign 처리로 그대로 전달된다
    private void verifyHubExists(UUID hubId) {
        hubClient.getHub(hubId);
    }

    // 관리자로 지정할 회원이 활성 상태의 COMPANY인지 user에 확인한다
    // requiredCompanyId: 관리자 변경이면 새 관리자가 소속돼야 하는 업체 ID, 생성이면 업체가 아직 없어 null(소속 확인 생략)
    private void verifyOwner(UUID ownerId, UUID requiredCompanyId) {
        UserResponse owner = fetchOwner(ownerId);

        if (!owner.active()) {
            throw ownerRuleViolation("비활성화된 회원은 업체 관리자로 지정할 수 없습니다.");
        }
        if (UserRole.from(owner.role()) != UserRole.COMPANY) {
            throw ownerRuleViolation("COMPANY 역할 회원만 업체 관리자로 지정할 수 있습니다.");
        }
        if (requiredCompanyId != null && !requiredCompanyId.equals(owner.companyId())) {
            throw ownerRuleViolation("이 업체 소속 회원만 업체 관리자로 지정할 수 있습니다.");
        }
    }

    // user의 404는 field가 userId라 요청 필드(ownerId)와 company 메시지로 바꿔 던진다. 다른 4xx는 그대로 전달
    private UserResponse fetchOwner(UUID ownerId) {
        try {
            return userClient.getUser(ownerId).data();
        } catch (FeignClientException e) {
            if (e.getStatus() == HttpStatus.NOT_FOUND.value()) {
                throw new BusinessException(RESOURCE_NOT_FOUND, "존재하지 않거나 탈퇴한 회원입니다.", "ownerId");
            }
            throw e;
        }
    }

    private BusinessException ownerRuleViolation(String message) {
        return new BusinessException(BUSINESS_RULE_VIOLATION, message, "ownerId");
    }
}
