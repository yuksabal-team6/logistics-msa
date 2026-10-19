package com.yuksabal.company.global.deletion;

import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.response.ApiErrorDetail;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.yuksabal.common.exception.CommonErrorCode.DEPENDENCY_FAILURE;
import static com.yuksabal.company.global.exception.CompanyErrorCode.HAS_ACTIVE_WORK;

// 남은 업무(허브 재고·진행 중 주문) 확인. 등록된 DeletionChecker 구현체를 모두 돌려 남은 업무를 모은다
// - 상품·업체 삭제 전 (#137), 업체 관리 허브 변경 전 (#161)
// - 남은 업무가 있으면 409 HAS_ACTIVE_WORK (details에 종류별 이유)
// - 하나라도 확인하지 못하면 502 (확인하지 못한 채로 진행하지 않는다)
// Feign 호출이라 삭제는 DB 트랜잭션 밖에서 부른다 (응답을 기다리는 동안 커넥션을 잡지 않도록)
@Slf4j
@Component
@RequiredArgsConstructor
public class DeletionGuard {

    // Spring이 DeletionChecker 구현체(@Component)를 모두 찾아 목록으로 넣어 준다
    private final List<DeletionChecker> checkers;

    public void validateDeletable(DeletionTarget target, UUID id) {
        validateNoActiveWork(target, id, target.getObjectName() + " 삭제할");
    }

    // 관리 허브를 바꾸면 이전 허브의 재고는 주문으로 빠질 수 없고, 진행 중 주문이 취소되면 복원 재고도 이전 허브로 돌아간다
    // → 업체 삭제와 같은 기준(업체 상품의 재고, 공급·요청 업체로 걸린 진행 중 주문)으로 확인한다
    public void validateHubChangeable(UUID companyId) {
        validateNoActiveWork(DeletionTarget.COMPANY, companyId, "업체의 관리 허브를 바꿀");
    }

    // action: 메시지의 "~ 수 없습니다" 앞부분 (예: "상품을 삭제할", "업체의 관리 허브를 바꿀")
    private void validateNoActiveWork(DeletionTarget target, UUID id, String action) {
        List<ApiErrorDetail> blockers = new ArrayList<>();
        for (DeletionChecker checker : checkers) {
            for (String reason : findBlockers(checker, target, id, action)) {
                blockers.add(new ApiErrorDetail(checker.type(), reason));
            }
        }
        if (!blockers.isEmpty()) {
            throw new BusinessException(HAS_ACTIVE_WORK,
                    "재고나 진행 중인 주문이 있어 " + action + " 수 없습니다.", blockers);
        }
    }

    // 구현체가 직접 판단한 예외(BusinessException)는 그대로 두고, 그 외(Feign 오류·타임아웃 등)는 502로 바꾼다
    private List<String> findBlockers(DeletionChecker checker, DeletionTarget target, UUID id, String action) {
        try {
            return checker.findBlockers(target, id);
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            // 상대 서비스 오류·타임아웃·4xx(계약 불일치 포함) 모두 "확인하지 못함"으로 보고 막는다
            log.error("남은 업무 확인 실패. type={}, {}={}", checker.type(), target.getIdName(), id, e);
            throw new BusinessException(DEPENDENCY_FAILURE,
                    "남은 업무를 확인하지 못해 " + action + " 수 없습니다.");
        }
    }
}
