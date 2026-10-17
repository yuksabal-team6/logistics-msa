package com.yuksabal.user.user.service;

import com.yuksabal.user.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

// #68 업체 관리자 확인. 삭제되지 않은 업체의 관리자는 삭제할 수 없다 (company 답변, 확인 실패면 차단)
// 관리 업체 조회 규칙(COMPANY 역할만 조회, 응답 이상은 502)은 CompanyOwnerGuard와 같다
@Component
@RequiredArgsConstructor
public class CompanyActiveWorkChecker implements ActiveWorkChecker {

    private final CompanyOwnerGuard companyOwnerGuard;

    @Override
    public Target target() {
        return Target.COMPANY;
    }

    @Override
    public List<ActiveWork> findActiveWork(User user) {
        return companyOwnerGuard.ownedCompanyIds(user).stream()
                .map(companyId -> new ActiveWork("company",
                        "업체(" + companyId + ")의 관리자입니다. 업체 관리자를 먼저 변경해 주세요."))
                .toList();
    }
}
