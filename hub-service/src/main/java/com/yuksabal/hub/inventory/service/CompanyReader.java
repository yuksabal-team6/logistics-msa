package com.yuksabal.hub.inventory.service;

import java.util.Optional;
import java.util.UUID;

// 재고 권한 확인에 필요한 업체 정보 조회. 어디서 가져오는지(company-service Feign)는 구현(CompanyFeignAdapter)이 맡는다
// 없거나 삭제된 업체면 empty. 그 상황을 403으로 할지는 쓰는 쪽(InventoryAccessPolicy)이 정한다
public interface CompanyReader {

    // 업체 관리자(ownerId)
    Optional<UUID> findOwnerId(UUID companyId);
}
