package com.yuksabal.user.user.service;

import com.yuksabal.common.auth.UserRole;
import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.user.user.client.CompanyClient;
import com.yuksabal.user.user.client.dto.CompanyResponse;
import com.yuksabal.user.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

import static com.yuksabal.common.exception.CommonErrorCode.BUSINESS_RULE_VIOLATION;
import static com.yuksabal.common.exception.CommonErrorCode.DEPENDENCY_FAILURE;

// 업체 관리자 보호 (#113, company 담당자와 합의)
// 업체의 owner_id가 회원을 가리키면 그 회원의 company_id도 그 업체를 가리켜야 한다.
// owner_id는 company가, company_id는 user가 관리하므로 user는 MASTER의 소속·역할 변경에서 이 관계를 지킨다
// - 업체 관리자는 COMPANY 역할이어야 지정되므로, 현재 역할이 COMPANY인 회원만 관리 업체를 조회한다
//   (다른 역할의 수정·역할 변경은 company-service 상태와 관계없이 동작한다)
// - 관리 업체 조회(#11)에 실패하면 막는다: 타임아웃·5xx는 common이 502, 응답이 비어 있으면 여기서 502
// - 계정 비활성화는 막지 않는다 (보안상 즉시 잠가야 할 때가 있고 되돌릴 수 있다)
@Component
@RequiredArgsConstructor
public class CompanyOwnerGuard {

    private static final String MESSAGE = "업체 관리자를 먼저 변경해 주세요.";

    private final CompanyClient companyClient;

    // MASTER 회원 수정에서 companyId가 바뀔 때. newCompanyId는 바뀐 값만 넘긴다 (변경 없음은 null)
    // 관리 업체 0개: 제한 없음 / 1개: 그 업체로만 지정 가능 / 2개 이상: 데이터 이상이라 막는다
    public void validateCompanyChange(User user, UUID newCompanyId) {
        if (newCompanyId == null || user.getRole() != UserRole.COMPANY) {
            return;
        }
        List<CompanyResponse> owned = ownedCompanies(user.getId());
        if (owned.isEmpty()) {
            return;
        }
        if (owned.size() > 1 || !owned.get(0).companyId().equals(newCompanyId)) {
            throw new BusinessException(BUSINESS_RULE_VIOLATION, MESSAGE, "companyId");
        }
    }

    // 역할 변경: COMPANY → 다른 역할이면 관리 업체가 없어야 한다
    public void validateRoleChange(User user, UserRole newRole) {
        if (user.getRole() != UserRole.COMPANY || newRole == UserRole.COMPANY) {
            return;
        }
        if (!ownedCompanies(user.getId()).isEmpty()) {
            throw new BusinessException(BUSINESS_RULE_VIOLATION, MESSAGE, "role");
        }
    }

    private List<CompanyResponse> ownedCompanies(UUID userId) {
        ApiResponse<List<CompanyResponse>> response = companyClient.getCompaniesByOwner(userId);
        if (response == null || response.data() == null) {
            // 계약상 관리 업체가 없어도 빈 목록이 온다. 비어 있으면 확인하지 못한 것으로 보고 막는다
            throw new BusinessException(DEPENDENCY_FAILURE);
        }
        return response.data();
    }
}
