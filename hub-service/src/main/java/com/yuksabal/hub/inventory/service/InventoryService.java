package com.yuksabal.hub.inventory.service;

import com.yuksabal.common.auth.UserRole;
import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.exception.CommonErrorCode;
import com.yuksabal.common.response.PageResponse;
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

import static com.yuksabal.common.util.PageableUtils.toStablePageable;
import static com.yuksabal.hub.inventory.repository.InventorySpecification.search;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InventoryService {

    // 목록 정렬 허용 필드 (API 명세 0.4: 정렬 필드는 API별로 제한)
    private static final Set<String> SORTABLE_FIELDS = Set.of("createdAt", "quantity");

    private final InventoryRepository inventoryRepository;

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
}
