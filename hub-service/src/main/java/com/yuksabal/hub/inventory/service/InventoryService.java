package com.yuksabal.hub.inventory.service;

import com.yuksabal.common.auth.UserRole;
import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.exception.CommonErrorCode;
import com.yuksabal.common.response.PageResponse;
import com.yuksabal.hub.hub.repository.HubRepository;
import com.yuksabal.hub.inventory.dto.request.InventorySearchRequest;
import com.yuksabal.hub.inventory.dto.response.InventoryResponse;
import com.yuksabal.hub.inventory.entity.Inventory;
import com.yuksabal.hub.inventory.repository.InventoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

import static com.yuksabal.common.util.PageableUtils.toStablePageable;
import static com.yuksabal.hub.inventory.repository.InventorySpecification.hubIdEquals;
import static com.yuksabal.hub.inventory.repository.InventorySpecification.search;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InventoryService {

    // 목록 정렬 허용 필드 (API 명세 0.4: 정렬 필드는 API별로 제한)
    private static final Set<String> SORTABLE_FIELDS = Set.of("createdAt", "quantity");

    private final InventoryRepository inventoryRepository;
    private final HubRepository hubRepository;

    // 전체 재고 목록은 MASTER만 조회할 수 있다. 없는 허브·상품 ID는 에러 없이 빈 목록
    public PageResponse<InventoryResponse> getInventories(InventorySearchRequest request, Pageable pageable, UserRole role) {
        if (role != UserRole.MASTER) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }

        Page<Inventory> inventories = inventoryRepository.findAll(
                search(request.hubId(), request.productId()),
                toStablePageable(pageable, SORTABLE_FIELDS));
        return PageResponse.from(inventories, InventoryResponse::from);
    }

    // 재고 상세는 "MASTER 또는 허용된 사용자"가 조회할 수 있다.
    // TODO 허용된 사용자 기준이 정해지면 재고의 허브·상품으로 권한을 확인한다 (지금은 MASTER만)
    // 삭제된 재고는 @SQLRestriction으로 조회되지 않아 없는 재고와 같이 404로 응답한다.
    public InventoryResponse getInventory(UUID inventoryId, UserRole role) {
        if (role != UserRole.MASTER) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }

        Inventory inventory = inventoryRepository.findById(inventoryId)
                .orElseThrow(() -> new BusinessException(
                        CommonErrorCode.RESOURCE_NOT_FOUND,
                        "존재하지 않는 재고입니다.",
                        "inventoryId"));
        return InventoryResponse.from(inventory);
    }

    // 허브별 재고는 "MASTER 또는 허용된 사용자"가 조회할 수 있다.
    // TODO 허용된 사용자 기준이 정해지면 허브로 권한을 확인한다 (지금은 MASTER만)
    // 없는 허브(삭제된 허브 포함)는 404, 허브는 있지만 재고가 없으면 빈 목록
    public PageResponse<InventoryResponse> getInventoriesByHub(UUID hubId, Pageable pageable, UserRole role) {
        if (role != UserRole.MASTER) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        if (!hubRepository.existsById(hubId)) {
            throw new BusinessException(
                    CommonErrorCode.RESOURCE_NOT_FOUND,
                    "존재하지 않는 허브입니다.",
                    "hubId");
        }

        Page<Inventory> inventories = inventoryRepository.findAll(
                hubIdEquals(hubId),
                toStablePageable(pageable, SORTABLE_FIELDS));
        return PageResponse.from(inventories, InventoryResponse::from);
    }
}
