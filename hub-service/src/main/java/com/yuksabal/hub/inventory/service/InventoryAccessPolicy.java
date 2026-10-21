package com.yuksabal.hub.inventory.service;

import com.yuksabal.common.auth.UserRole;
import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.exception.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

// 재고 API 권한. 권한이 없으면 403 FORBIDDEN
// 권한은 Gateway(#133)와 이 정책이 둘 다 검사한다 (다층 방어)
// Gateway를 거치지 않는 호출(내부망 직접 호출, Gateway 규칙 누락)에도 서비스가 스스로 막고,
// COMPANY의 상품 소유권처럼 Gateway가 알 수 없는 조건은 여기서만 판단한다
// 내부 API(재고 확인, 주문 재고 차감·복원)는 사용자 권한을 검사하지 않아 이 정책을 쓰지 않는다
@Component
@RequiredArgsConstructor
public class InventoryAccessPolicy {

    private final CompanyReader companyReader;

    // 재고 목록·상세·허브별·로그 조회, 수량 조정, 입고는 MASTER만
    public void checkMaster(UserRole role) {
        if (role != UserRole.MASTER) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
    }

    // 상품별 재고는 MASTER 또는 COMPANY. 재고를 조회하기 전에 역할만 확인하고,
    // COMPANY의 업체 소유권은 재고의 업체를 알아야 해서 조회 후 checkProductOwner로 확인한다
    public void checkProductReadable(UserRole role) {
        if (role != UserRole.MASTER && role != UserRole.COMPANY) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
    }

    // COMPANY는 재고 업체의 관리자(ownerId) 본인만 허용. MASTER는 company를 호출하지 않는다
    // 업체가 없거나 삭제되면 소유권을 확인할 수 없어 403
    public void checkProductOwner(UUID companyId, UUID userId, UserRole role) {
        if (role != UserRole.COMPANY) {
            return;
        }
        boolean owner = companyReader.findOwnerId(companyId)
                .map(userId::equals)
                .orElse(false);
        if (!owner) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
    }
}
