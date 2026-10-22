package com.yuksabal.hub.inventory.application;

import com.yuksabal.common.auth.UserRole;
import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.exception.CommonErrorCode;
import com.yuksabal.common.response.ApiErrorDetail;
import com.yuksabal.hub.global.exception.HubErrorCode;
import com.yuksabal.hub.global.exception.HubExceptions;
import com.yuksabal.hub.hub.domain.Hub;
import com.yuksabal.hub.hub.domain.HubRepository;
import com.yuksabal.hub.inventory.presentation.dto.request.InventoryInboundRequest;
import com.yuksabal.hub.inventory.presentation.dto.request.InventoryQuantityUpdateRequest;
import com.yuksabal.hub.inventory.presentation.dto.response.InventoryResponse;
import com.yuksabal.hub.inventory.domain.Inventory;
import com.yuksabal.hub.inventory.domain.InventoryChangeType;
import com.yuksabal.hub.inventory.domain.InventoryLog;
import com.yuksabal.hub.inventory.domain.InventoryLogRepository;
import com.yuksabal.hub.inventory.domain.InventoryRepository;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

// 관리자 재고 변경 (수량 조정, 입고). 조회는 InventoryQueryService, 주문 재고 차감·복원은 StockService
// 메서드마다 트랜잭션이 달라(조정은 하나의 트랜잭션, 입고는 company 호출을 트랜잭션 밖에서) 클래스 단위 @Transactional을 두지 않는다
// 권한 검사는 InventoryAccessPolicy가 맡는다 (Gateway와 함께 검사하는 다층 방어)
@Service
@RequiredArgsConstructor
public class InventoryCommandService {

    // schema.sql의 (hub_id, product_id) 부분 유니크 인덱스
    private static final String INVENTORY_UNIQUE_INDEX = "uk_inventories_hub_id_product_id";

    private final InventoryRepository inventoryRepository;
    private final InventoryLogRepository inventoryLogRepository;
    private final HubRepository hubRepository;
    private final ProductReader productReader;
    private final InventoryAccessPolicy accessPolicy;
    private final TransactionTemplate transactionTemplate;

    // 관리자가 실사 결과로 재고 수량을 직접 바꾼다 (MASTER). 조회할 때 본 수량과 현재 수량이 같을 때만 조정한다
    // 엔티티를 먼저 조회하지 않고 조건부 UPDATE부터 실행해, 1차 캐시에 예전 수량이 남지 않게 한다
    // 값이 같으면 그 사이 변경의 순변화량이 0이라 조정 결과가 맞으므로 수량만 비교한다
    @Transactional
    public InventoryResponse adjustQuantity(UUID inventoryId, InventoryQuantityUpdateRequest request, UUID userId, UserRole role) {
        accessPolicy.checkMaster(role);
        // 조회한 수량과 같은 값으로 조정하는 요청은 DTO 검증(isQuantityChanged)에서 400으로 막힌다
        int expectedQuantity = request.expectedQuantity();
        int quantity = request.quantity();

        int updated = inventoryRepository.updateQuantityIfMatches(
                inventoryId, expectedQuantity, quantity, LocalDateTime.now(), userId);
        if (updated == 0) {
            // 재고가 없으면 404, 있으면 그 사이 수량이 바뀐 것이라 409 (현재 수량은 다시 바뀔 수 있어 참고용)
            Inventory current = findInventory(inventoryId);
            throw HubExceptions.withCurrentQuantity(HubErrorCode.INVENTORY_QUANTITY_CONFLICT, current.getQuantity());
        }

        Inventory inventory = findInventory(inventoryId);
        InventoryChangeType changeType = quantity > expectedQuantity
                ? InventoryChangeType.ADJUST_INCREASE
                : InventoryChangeType.ADJUST_DECREASE;
        inventoryLogRepository.save(InventoryLog.builder()
                .inventory(inventory)
                .changeType(changeType)
                .quantity(Math.abs(quantity - expectedQuantity))
                .build());
        return InventoryResponse.from(inventory);
    }

    // 허브에 상품을 입고한다 (MASTER). 재고가 있으면 수량을 늘리고, 없으면 새로 만든다
    // 상품 활성 상태는 바뀔 수 있어 재고 유무와 관계없이 입고마다 company #1로 확인한다 (#139)
    // 업체를 비활성화하면 소속 상품도 비활성화되므로 상품 active만 보면 비활성 업체 상품도 막힌다
    // 관리 허브도 바뀔 수 있어 입고마다 확인한다 (#175). 허브 변경 후 이전 허브에 남은 수량 0 재고로 입고되면 재고가 두 허브로 나뉜다
    // company 호출(#1)은 트랜잭션 밖에서 하고, 재고 변경만 transactionTemplate으로 감싼다 (응답을 기다리는 동안 커넥션을 잡지 않도록)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public InventoryResponse inbound(InventoryInboundRequest request, UUID userId, UserRole role) {
        accessPolicy.checkMaster(role);
        // 값싼 허브 확인을 먼저 해, 없는 허브면 company를 호출하지 않는다 (허브 404 → 상품 404 순서)
        if (!hubRepository.existsById(request.hubId())) {
            throw HubExceptions.hubNotFound();
        }

        // 없거나 삭제된 상품이면 company-service가 404로 응답한다
        ProductInfo product = productReader.findProduct(request.productId())
                .orElseThrow(HubExceptions::productNotFound);
        if (!product.active()) {
            throw new BusinessException(
                    CommonErrorCode.BUSINESS_RULE_VIOLATION,
                    "비활성 상품은 입고할 수 없습니다.",
                    "productId");
        }
        // 상품 재고는 업체 관리 허브 한 곳에만 둔다
        if (!request.hubId().equals(product.hubId())) {
            throw new BusinessException(
                    CommonErrorCode.BUSINESS_RULE_VIOLATION,
                    "상품 업체의 관리 허브에만 입고할 수 있습니다.",
                    List.of(new ApiErrorDetail("hubId", "상품 업체의 관리 허브는 " + product.hubId() + "입니다.")));
        }

        return transactionTemplate.execute(status -> saveInbound(request, product, userId));
    }

    // 입고의 DB 작업 (transactionTemplate 안에서 실행). 예외가 나면 재고 변경·로그가 함께 롤백된다
    private InventoryResponse saveInbound(InventoryInboundRequest request, ProductInfo product, UUID userId) {
        // 허브는 고정 데이터라 삭제되지 않아, 앞에서 확인한 허브를 다시 조회하지 않고 참조만 쓴다
        Hub hub = hubRepository.getReferenceById(request.hubId());
        Inventory inventory = inventoryRepository.findByHubIdAndProductId(request.hubId(), request.productId())
                .map(existing -> increaseInventory(existing, request.quantity(), userId))
                .orElseGet(() -> createInventory(hub, product, request));

        inventoryLogRepository.save(InventoryLog.builder()
                .inventory(inventory)
                .changeType(InventoryChangeType.INBOUND)
                .quantity(request.quantity())
                .build());
        return InventoryResponse.from(inventory);
    }

    // 현재 수량에 더하는 원자 UPDATE라 동시 입고·주문 차감과 덮어쓰지 않는다. UPDATE 후 늘어난 수량으로 다시 조회한다
    private Inventory increaseInventory(Inventory inventory, int quantity, UUID userId) {
        inventoryRepository.increaseQuantity(inventory.getId(), quantity, LocalDateTime.now(), userId);
        return findInventory(inventory.getId());
    }

    // 관리 허브 확인은 inbound에서 끝났다
    // 같은 허브·상품을 동시에 처음 입고하면 늦은 쪽이 (hub_id, product_id) 유니크 인덱스 위반으로 실패해 409로 응답한다
    private Inventory createInventory(Hub hub, ProductInfo product, InventoryInboundRequest request) {
        try {
            // 유니크 위반이 여기서 나도록 바로 flush 한다
            return inventoryRepository.saveAndFlush(Inventory.builder()
                    .hub(hub)
                    .productId(request.productId())
                    .companyId(product.companyId())
                    .quantity(request.quantity())
                    .build());
        } catch (DataIntegrityViolationException e) {
            // 다른 무결성 위반이면 원래 예외를 그대로 던진다
            if (e.getCause() instanceof ConstraintViolationException cause
                    && INVENTORY_UNIQUE_INDEX.equals(cause.getConstraintName())) {
                throw new BusinessException(HubErrorCode.INVENTORY_CREATE_CONFLICT);
            }
            throw e;
        }
    }

    private Inventory findInventory(UUID inventoryId) {
        return inventoryRepository.findById(inventoryId)
                .orElseThrow(() -> HubExceptions.inventoryNotFound("inventoryId"));
    }
}
