package com.yuksabal.hub.inventory.service;

import com.yuksabal.common.auth.UserRole;
import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.exception.CommonErrorCode;
import com.yuksabal.common.feign.FeignClientException;
import com.yuksabal.common.response.ApiErrorDetail;
import com.yuksabal.common.response.PageResponse;
import com.yuksabal.hub.global.exception.HubErrorCode;
import com.yuksabal.hub.hub.entity.Hub;
import com.yuksabal.hub.hub.repository.HubRepository;
import com.yuksabal.hub.inventory.client.CompanyClient;
import com.yuksabal.hub.inventory.client.dto.CompanyResponse;
import com.yuksabal.hub.inventory.client.dto.ProductResponse;
import com.yuksabal.hub.inventory.dto.request.InventoryInboundRequest;
import com.yuksabal.hub.inventory.dto.request.InventoryLogSearchRequest;
import com.yuksabal.hub.inventory.dto.request.InventoryQuantityUpdateRequest;
import com.yuksabal.hub.inventory.dto.request.InventorySearchRequest;
import com.yuksabal.hub.inventory.dto.request.InventoryStockRequest;
import com.yuksabal.hub.inventory.dto.response.InventoryLogResponse;
import com.yuksabal.hub.inventory.dto.response.InventoryResponse;
import com.yuksabal.hub.inventory.dto.response.InventoryStockResponse;
import com.yuksabal.hub.inventory.entity.Inventory;
import com.yuksabal.hub.inventory.entity.InventoryChangeType;
import com.yuksabal.hub.inventory.entity.InventoryLog;
import com.yuksabal.hub.inventory.repository.InventoryLogRepository;
import com.yuksabal.hub.inventory.repository.InventoryLogSpecification;
import com.yuksabal.hub.inventory.repository.InventoryRepository;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
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

    // 로그 목록 정렬 허용 필드 (변경 순서로만 본다)
    private static final Set<String> LOG_SORTABLE_FIELDS = Set.of("createdAt");

    // data.sql의 (hub_id, product_id) 부분 유니크 인덱스
    private static final String INVENTORY_UNIQUE_INDEX = "uk_inventories_hub_id_product_id";

    private final InventoryRepository inventoryRepository;
    private final InventoryLogRepository inventoryLogRepository;
    private final HubRepository hubRepository;
    private final CompanyClient companyClient;
    private final TransactionTemplate transactionTemplate;

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

    // 재고 변경 이력은 MASTER만 조회할 수 있다. 없는 재고·허브·주문 ID는 에러 없이 빈 목록
    public PageResponse<InventoryLogResponse> getInventoryLogs(InventoryLogSearchRequest request, Pageable pageable, UserRole role) {
        if (role != UserRole.MASTER) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }

        Page<InventoryLog> logs = inventoryLogRepository.findAll(
                InventoryLogSpecification.search(request.inventoryId(), request.hubId(), request.orderId(), request.changeType()),
                toStablePageable(pageable, LOG_SORTABLE_FIELDS));
        return PageResponse.from(logs, InventoryLogResponse::from);
    }

    // 재고 상세는 "MASTER 또는 허용된 사용자"가 조회할 수 있다.
    // TODO 허용된 사용자 기준이 정해지면 재고의 허브·상품으로 권한을 확인한다 (지금은 MASTER만)
    // 삭제된 재고는 @SQLRestriction으로 조회되지 않아 없는 재고와 같이 404로 응답한다.
    public InventoryResponse getInventory(UUID inventoryId, UserRole role) {
        if (role != UserRole.MASTER) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }

        return InventoryResponse.from(findInventory(inventoryId));
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

    // 상품별 재고는 MASTER 또는 해당 상품의 업체(COMPANY)가 조회할 수 있다.
    // 상품 재고는 업체 관리 허브 한 곳에만 생겨 단건으로 응답하고, 없으면 404 (관리 허브가 바뀐 경우는 findSingleInventoryByProduct)
    // COMPANY는 재고의 업체를 알아야 소유권을 판단할 수 있어 조회 후에 403을 확인한다
    // company 호출(#2)을 기다리는 동안 DB 커넥션을 잡지 않도록 트랜잭션 없이 실행한다 (재고 조회는 Repository가 짧게 처리)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public InventoryResponse getInventoryByProduct(UUID productId, UUID userId, UserRole role) {
        if (role != UserRole.MASTER && role != UserRole.COMPANY) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }

        Inventory inventory = findSingleInventoryByProduct(productId);
        if (role == UserRole.COMPANY) {
            validateCompanyOwner(inventory.getCompanyId(), userId);
        }
        return InventoryResponse.from(inventory);
    }

    // company-service가 상품·업체 삭제 전에 호출한다 (내부 API, #137). 남은 수량이 0보다 큰 재고가 있으면 true
    // 수량 0인 재고는 이력이라 false. 재고가 없는 상품·업체도 404가 아니라 false
    public InventoryStockResponse hasStock(InventoryStockRequest request) {
        boolean hasStock = request.productId() != null
                ? inventoryRepository.existsByProductIdAndQuantityGreaterThan(request.productId(), 0)
                : inventoryRepository.existsByCompanyIdAndQuantityGreaterThan(request.companyId(), 0);
        return new InventoryStockResponse(hasStock);
    }

    // 관리자가 실사 결과로 재고 수량을 직접 바꾼다 (MASTER). 조회할 때 본 수량과 현재 수량이 같을 때만 조정한다
    // 엔티티를 먼저 조회하지 않고 조건부 UPDATE부터 실행해, 1차 캐시에 예전 수량이 남지 않게 한다
    // 값이 같으면 그 사이 변경의 순변화량이 0이라 조정 결과가 맞으므로 수량만 비교한다
    @Transactional
    public InventoryResponse adjustQuantity(UUID inventoryId, InventoryQuantityUpdateRequest request, UUID userId, UserRole role) {
        if (role != UserRole.MASTER) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        int expectedQuantity = request.expectedQuantity();
        int quantity = request.quantity();
        if (expectedQuantity == quantity) {
            throw new BusinessException(
                    CommonErrorCode.VALIDATION_ERROR,
                    "조정할 재고 수량이 조회한 수량과 같습니다.",
                    "quantity");
        }

        int updated = inventoryRepository.updateQuantityIfMatches(
                inventoryId, expectedQuantity, quantity, LocalDateTime.now(), userId);
        if (updated == 0) {
            // 재고가 없으면 404, 있으면 그 사이 수량이 바뀐 것이라 409 (현재 수량은 다시 바뀔 수 있어 참고용)
            Inventory current = findInventory(inventoryId);
            throw new BusinessException(
                    HubErrorCode.INVENTORY_QUANTITY_CONFLICT,
                    HubErrorCode.INVENTORY_QUANTITY_CONFLICT.getMessage(),
                    List.of(new ApiErrorDetail("quantity", "현재 재고는 " + current.getQuantity() + "개입니다.")));
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
    // company 호출(#1)은 트랜잭션 밖에서 하고, 재고 변경만 transactionTemplate으로 감싼다 (응답을 기다리는 동안 커넥션을 잡지 않도록)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public InventoryResponse inbound(InventoryInboundRequest request, UUID userId, UserRole role) {
        if (role != UserRole.MASTER) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        // 값싼 허브 확인을 먼저 해, 없는 허브면 company를 호출하지 않는다 (허브 404 → 상품 404 순서)
        if (!hubRepository.existsById(request.hubId())) {
            throw new BusinessException(
                    CommonErrorCode.RESOURCE_NOT_FOUND,
                    "존재하지 않는 허브입니다.",
                    "hubId");
        }

        ProductResponse product = findProduct(request.productId());
        if (!product.active()) {
            throw new BusinessException(
                    CommonErrorCode.BUSINESS_RULE_VIOLATION,
                    "비활성 상품은 입고할 수 없습니다.",
                    "productId");
        }

        return transactionTemplate.execute(status -> saveInbound(request, product, userId));
    }

    // 입고의 DB 작업 (transactionTemplate 안에서 실행). 예외가 나면 재고 변경·로그가 함께 롤백된다
    private InventoryResponse saveInbound(InventoryInboundRequest request, ProductResponse product, UUID userId) {
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

    // 상품 업체의 관리 허브에만 재고를 만든다 (상품 재고는 업체 관리 허브 한 곳에만 둔다)
    // 같은 허브·상품을 동시에 처음 입고하면 늦은 쪽이 (hub_id, product_id) 유니크 인덱스 위반으로 실패해 409로 응답한다
    private Inventory createInventory(Hub hub, ProductResponse product, InventoryInboundRequest request) {
        if (!hub.getId().equals(product.hubId())) {
            throw new BusinessException(
                    CommonErrorCode.BUSINESS_RULE_VIOLATION,
                    "상품 업체의 관리 허브에만 입고할 수 있습니다.",
                    List.of(new ApiErrorDetail("hubId", "상품 업체의 관리 허브는 " + product.hubId() + "입니다.")));
        }

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

    // 없거나 삭제된 상품이면 company-service가 404로 응답한다
    private ProductResponse findProduct(UUID productId) {
        try {
            return companyClient.getProduct(productId).data();
        } catch (FeignClientException e) {
            if (e.getStatus() == HttpStatus.NOT_FOUND.value()) {
                throw new BusinessException(
                        CommonErrorCode.RESOURCE_NOT_FOUND,
                        "존재하지 않는 상품입니다.",
                        "productId");
            }
            throw e;
        }
    }

    private Inventory findInventory(UUID inventoryId) {
        return inventoryRepository.findById(inventoryId)
                .orElseThrow(() -> new BusinessException(
                        CommonErrorCode.RESOURCE_NOT_FOUND,
                        "존재하지 않는 재고입니다.",
                        "inventoryId"));
    }

    // 업체 관리 허브가 바뀌면 이전 허브에 재고 행이 이력으로 남아(수량 0, 삭제하지 않음) 같은 상품 재고가 여러 건이 된다
    // 실물이 있는 재고(수량 > 0)만 현재 재고로 보고, 전부 0이면 가장 최근에 생긴 재고(현재 관리 허브)로 응답한다
    // 수량이 남은 재고가 두 허브 이상이면 어느 쪽인지 정할 수 없어 409 (company가 재고·주문이 남으면 허브 변경을 막는다)
    private Inventory findSingleInventoryByProduct(UUID productId) {
        List<Inventory> inventories = inventoryRepository.findByProductId(productId);
        if (inventories.isEmpty()) {
            throw new BusinessException(
                    CommonErrorCode.RESOURCE_NOT_FOUND,
                    "존재하지 않는 재고입니다.",
                    "productId");
        }
        if (inventories.size() == 1) {
            return inventories.get(0);
        }

        List<Inventory> inStock = inventories.stream()
                .filter(inventory -> inventory.getQuantity() > 0)
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

    // 업체 관리자(ownerId) 본인만 허용. 업체가 없거나 삭제되면(404) 소유권을 확인할 수 없어 403
    private void validateCompanyOwner(UUID companyId, UUID userId) {
        CompanyResponse company;
        try {
            company = companyClient.getCompany(companyId).data();
        } catch (FeignClientException e) {
            if (e.getStatus() == HttpStatus.NOT_FOUND.value()) {
                throw new BusinessException(CommonErrorCode.FORBIDDEN);
            }
            throw e;
        }
        if (!company.ownerId().equals(userId)) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
    }
}
