package com.yuksabal.hub.inventory.application;

import com.yuksabal.common.auth.UserRole;
import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.hub.global.exception.HubErrorCode;
import com.yuksabal.hub.global.exception.HubExceptions;
import com.yuksabal.hub.hub.domain.HubRepository;
import com.yuksabal.hub.inventory.application.dto.InventoryLogResult;
import com.yuksabal.hub.inventory.application.dto.InventoryLogSearchCondition;
import com.yuksabal.hub.inventory.application.dto.InventoryResult;
import com.yuksabal.hub.inventory.application.dto.InventorySearchCondition;
import com.yuksabal.hub.inventory.application.dto.InventoryStockCondition;
import com.yuksabal.hub.inventory.domain.Inventory;
import com.yuksabal.hub.inventory.domain.InventoryLogRepository;
import com.yuksabal.hub.inventory.domain.InventoryLogSpecification;
import com.yuksabal.hub.inventory.domain.InventoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.yuksabal.common.util.PageableUtils.toStablePageable;
import static com.yuksabal.hub.inventory.domain.InventorySpecification.hubIdEquals;
import static com.yuksabal.hub.inventory.domain.InventorySpecification.search;

// 재고 조회 (목록·상세·허브별·상품별·변경 이력, 내부 API 재고 보유 여부). 변경은 InventoryCommandService
// 권한 검사는 InventoryAccessPolicy가 맡는다 (Gateway와 함께 검사하는 다층 방어)
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InventoryQueryService {

    // 목록 정렬 허용 필드 (API 명세 0.4: 정렬 필드는 API별로 제한)
    private static final Set<String> SORTABLE_FIELDS = Set.of("createdAt", "quantity");

    // 로그 목록 정렬 허용 필드 (변경 순서로만 본다)
    private static final Set<String> LOG_SORTABLE_FIELDS = Set.of("createdAt");

    private final InventoryRepository inventoryRepository;
    private final InventoryLogRepository inventoryLogRepository;
    private final HubRepository hubRepository;
    private final InventoryAccessPolicy accessPolicy;

    // 전체 재고 목록은 MASTER만 조회할 수 있다. 없는 허브·상품 ID는 에러 없이 빈 목록
    public Page<InventoryResult> getInventories(InventorySearchCondition condition, Pageable pageable, UserRole role) {
        accessPolicy.checkMaster(role);

        return inventoryRepository.findAll(
                        search(condition.hubId(), condition.productId()),
                        toStablePageable(pageable, SORTABLE_FIELDS))
                .map(InventoryResult::from);
    }

    // 재고 변경 이력은 MASTER만 조회할 수 있다. 없는 재고·허브·주문 ID는 에러 없이 빈 목록
    public Page<InventoryLogResult> getInventoryLogs(InventoryLogSearchCondition condition, Pageable pageable, UserRole role) {
        accessPolicy.checkMaster(role);

        return inventoryLogRepository.findAll(
                        InventoryLogSpecification.search(
                                condition.inventoryId(), condition.hubId(), condition.orderId(), condition.changeType()),
                        toStablePageable(pageable, LOG_SORTABLE_FIELDS))
                .map(InventoryLogResult::from);
    }

    // 재고 상세는 "MASTER 또는 허용된 사용자"가 조회할 수 있다.
    // TODO 허용된 사용자 기준이 정해지면 재고의 허브·상품으로 권한을 확인한다 (지금은 MASTER만)
    // 삭제된 재고는 @SQLRestriction으로 조회되지 않아 없는 재고와 같이 404로 응답한다.
    public InventoryResult getInventory(UUID inventoryId, UserRole role) {
        accessPolicy.checkMaster(role);

        return inventoryRepository.findById(inventoryId)
                .map(InventoryResult::from)
                .orElseThrow(() -> HubExceptions.inventoryNotFound("inventoryId"));
    }

    // 허브별 재고는 "MASTER 또는 허용된 사용자"가 조회할 수 있다.
    // TODO 허용된 사용자 기준이 정해지면 허브로 권한을 확인한다 (지금은 MASTER만)
    // 없는 허브(삭제된 허브 포함)는 404, 허브는 있지만 재고가 없으면 빈 목록
    public Page<InventoryResult> getInventoriesByHub(UUID hubId, Pageable pageable, UserRole role) {
        accessPolicy.checkMaster(role);
        if (!hubRepository.existsById(hubId)) {
            throw HubExceptions.hubNotFound();
        }

        return inventoryRepository.findAll(
                        hubIdEquals(hubId),
                        toStablePageable(pageable, SORTABLE_FIELDS))
                .map(InventoryResult::from);
    }

    // 상품별 재고는 MASTER 또는 해당 상품의 업체(COMPANY)가 조회할 수 있다.
    // 상품 재고는 업체 관리 허브 한 곳에만 생겨 단건으로 응답하고, 없으면 404 (관리 허브가 바뀐 경우는 findSingleInventoryByProduct)
    // COMPANY는 재고의 업체를 알아야 소유권을 판단할 수 있어 조회 후에 403을 확인한다
    // company 호출(#2)을 기다리는 동안 DB 커넥션을 잡지 않도록 트랜잭션 없이 실행한다 (재고 조회는 Repository가 짧게 처리)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public InventoryResult getInventoryByProduct(UUID productId, UUID userId, UserRole role) {
        accessPolicy.checkProductReadable(role);

        Inventory inventory = findSingleInventoryByProduct(productId);
        accessPolicy.checkProductOwner(inventory.getCompanyId(), userId, role);
        return InventoryResult.from(inventory);
    }

    // company-service가 상품·업체 삭제 전에 호출한다 (내부 API, #137). 남은 수량이 0보다 큰 재고가 있으면 true
    // 수량 0인 재고는 이력이라 false. 재고가 없는 상품·업체도 404가 아니라 false
    public boolean hasStock(InventoryStockCondition condition) {
        return condition.productId() != null
                ? inventoryRepository.existsByProductIdAndQuantityValueGreaterThan(condition.productId(), 0)
                : inventoryRepository.existsByCompanyIdAndQuantityValueGreaterThan(condition.companyId(), 0);
    }

    // 업체 관리 허브가 바뀌면 이전 허브에 재고 행이 이력으로 남아(수량 0, 삭제하지 않음) 같은 상품 재고가 여러 건이 된다
    // 실물이 있는 재고(수량 > 0)만 현재 재고로 보고, 전부 0이면 가장 최근에 생긴 재고(현재 관리 허브)로 응답한다
    // 수량이 남은 재고가 두 허브 이상이면 어느 쪽인지 정할 수 없어 409 (company가 재고·주문이 남으면 허브 변경을 막는다)
    private Inventory findSingleInventoryByProduct(UUID productId) {
        List<Inventory> inventories = inventoryRepository.findByProductId(productId);
        if (inventories.isEmpty()) {
            throw HubExceptions.inventoryNotFound("productId");
        }
        if (inventories.size() == 1) {
            return inventories.get(0);
        }

        List<Inventory> inStock = inventories.stream()
                .filter(Inventory::hasStock)
                .toList();
        if (inStock.size() == 1) {
            return inStock.get(0);
        }
        if (inStock.isEmpty()) {
            return inventories.stream()
                    .max(Comparator.comparing(Inventory::getCreatedAt))
                    .orElseThrow();
        }
        throw new BusinessException(HubErrorCode.INVENTORY_CONFLICT);
    }
}
