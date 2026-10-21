package com.yuksabal.hub.inventory.service;

import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.exception.CommonErrorCode;
import com.yuksabal.common.response.ApiErrorDetail;
import com.yuksabal.hub.global.exception.HubErrorCode;
import com.yuksabal.hub.global.exception.HubExceptions;
import com.yuksabal.hub.inventory.dto.request.StockRequest;
import com.yuksabal.hub.inventory.dto.response.StockDecreaseResponse;
import com.yuksabal.hub.inventory.dto.response.StockRestoreResponse;
import com.yuksabal.hub.inventory.entity.Inventory;
import com.yuksabal.hub.inventory.entity.InventoryChangeType;
import com.yuksabal.hub.inventory.entity.InventoryLog;
import com.yuksabal.hub.inventory.repository.InventoryLogRepository;
import com.yuksabal.hub.inventory.repository.InventoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// order-service 주문 재고 차감·복원 (내부 API #3, #4). 같은 (orderId, 유형)이면 변경 없이 성공 (결정 #18)
// 동시 중복 요청의 유니크 위반을 트랜잭션 밖에서 잡아야 해서 클래스 단위 @Transactional 대신 TransactionTemplate을 쓴다
// 호출자는 트랜잭션 밖에서 불러야 한다. 이미 트랜잭션 안이면 TransactionTemplate이 거기에 참여해(REQUIRED)
// 유니크 위반 후 재조회가 같은 실패한 트랜잭션에서 실행된다
@Service
@RequiredArgsConstructor
public class StockService {

    private final InventoryRepository inventoryRepository;
    private final InventoryLogRepository inventoryLogRepository;
    private final TransactionTemplate transactionTemplate;

    // 같은 재고의 요청은 재고 행 잠금으로 줄을 서므로 같은 주문의 동시 차감도 보통 로그 확인에서 걸러진다.
    // 그래도 둘 다 통과하면 늦은 쪽은 DECREASE 로그 INSERT가 유니크 위반으로 실패해 차감까지 롤백되고,
    // 먼저 커밋된 차감 결과로 성공 응답한다 (유니크 인덱스 = 마지막 방어선)
    public StockDecreaseResponse decreaseStock(StockRequest request, UUID userId) {
        try {
            return transactionTemplate.execute(status -> decrease(request, userId));
        } catch (DataIntegrityViolationException e) {
            // 차감 로그가 없으면 다른 무결성 위반이므로 원래 예외를 그대로 던진다
            return findOrderLog(request.orderId(), InventoryChangeType.DECREASE)
                    .map(log -> StockDecreaseResponse.of(log.getInventory(), request.orderId()))
                    .orElseThrow(() -> e);
        }
    }

    // 재고 차감
    private StockDecreaseResponse decrease(StockRequest request, UUID userId) {
        UUID orderId = request.orderId();

        // 1. 재고 행을 먼저 잠근다 (없으면 404). 로그 확인보다 먼저 잠가야 차감·복원이 동시에 와도
        // 뒤에 온 요청이 앞 요청의 로그를 본다. 로그 확인 뒤에 잠그면 그 사이에 복원이 끼어들 수 있다
        Inventory locked = findInventoryForUpdate(request);

        // 2. 이미 차감한 주문이면 변경 없이 성공
        if (findOrderLog(orderId, InventoryChangeType.DECREASE).isPresent()) {
            return StockDecreaseResponse.of(locked, orderId);
        }

        // 3. 이미 복원(보상)된 주문이면 차감하지 않는다.
        // 차감 요청이 타임아웃된 뒤 order가 복원을 보냈고, 지연된 차감 요청이 그보다 늦게 도착한 경우
        if (findOrderLog(orderId, InventoryChangeType.RESTORE).isPresent()) {
            return StockDecreaseResponse.of(locked, orderId);
        }

        // 4. 조건부 차감. 잠금을 잡고 있어 0이면 수량 부족뿐이다 (잠금 시점 수량 그대로)
        int updated = inventoryRepository.decreaseQuantityIfEnough(
                request.hubId(), request.productId(), request.quantity(), LocalDateTime.now(), userId);
        if (updated == 0) {
            throw HubExceptions.withCurrentQuantity(HubErrorCode.INSUFFICIENT_STOCK, locked.getQuantity());
        }

        // 5. 차감 후 수량으로 다시 조회한다 (UPDATE가 영속성 컨텍스트를 비워 차감 전 엔티티는 남지 않는다)
        Inventory inventory = findInventory(request);
        // 유니크 위반이 이 트랜잭션 안에서 나도록 바로 flush 한다 (커밋 시점 flush면 위반 원인 파악이 어렵다)
        inventoryLogRepository.saveAndFlush(InventoryLog.builder()
                .inventory(inventory)
                .orderId(orderId)
                .changeType(InventoryChangeType.DECREASE)
                .quantity(request.quantity())
                .build());
        return StockDecreaseResponse.of(inventory, orderId);
    }

    // 배송 생성 실패 보상·주문 취소 시 차감했던 재고를 되돌린다. 차감과 같이 재고 행 잠금 → 로그 확인 순서로 처리하고,
    // 같은 주문의 동시 복원은 RESTORE 로그 유니크 위반으로 롤백한 뒤 먼저 커밋된 복원 결과로 성공 응답한다
    public StockRestoreResponse restoreStock(StockRequest request, UUID userId) {
        try {
            return transactionTemplate.execute(status -> restore(request, userId));
        } catch (DataIntegrityViolationException e) {
            // 복원 로그가 없으면 다른 무결성 위반이므로 원래 예외를 그대로 던진다
            return findOrderLog(request.orderId(), InventoryChangeType.RESTORE)
                    .map(log -> StockRestoreResponse.of(log.getInventory(), log.getQuantity(), request.orderId()))
                    .orElseThrow(() -> e);
        }
    }

    // 재고 복원
    private StockRestoreResponse restore(StockRequest request, UUID userId) {
        UUID orderId = request.orderId();

        // 1. 재고 행을 먼저 잠근다 (없으면 404). 차감이 처리 중이면 끝날 때까지 기다렸다가 그 DECREASE 로그를 본다
        // 재고 행이 없으면 RESTORE 로그(inventory_id 필수)도 남길 수 없고, 늦게 온 차감도 같은 이유로 404라 재고가 깎이지 않는다
        Inventory locked = findInventoryForUpdate(request);

        // 2. 이미 복원한 주문이면 변경 없이 성공 (처음 복원한 수량으로 응답)
        Optional<InventoryLog> restored = findOrderLog(orderId, InventoryChangeType.RESTORE);
        if (restored.isPresent()) {
            return StockRestoreResponse.of(locked, restored.get().getQuantity(), orderId);
        }

        // 3. 차감 기록이 없으면 재고는 그대로 두고, 복원 요청이 왔었다는 표시로 RESTORE 로그(0)만 남긴다
        // 차감 요청이 타임아웃돼 실제로는 차감되지 않은 경우. 이 로그가 있어야 늦게 도착한 차감을 막을 수 있다
        Optional<InventoryLog> decreased = findOrderLog(orderId, InventoryChangeType.DECREASE);
        if (decreased.isEmpty()) {
            saveRestoreLog(locked, orderId, 0);
            return StockRestoreResponse.of(locked, 0, orderId);
        }

        // 4. 차감한 재고와 복원 요청의 재고(허브 + 상품)가 같은지 확인한다. 다르면 아무것도 쓰지 않고 422로 끝낸다
        // 그대로 진행하면 차감된 적 없는 재고가 늘어나고, 그 재고에 RESTORE 로그가 남아
        // 올바른 허브·상품으로 다시 복원해도 "이미 복원됨"으로 처리돼 원래 재고는 영영 복원되지 않는다
        // 같은 orderId는 허브·상품이 같다는 전제(주문 = 단일 상품, 수정 불가)라 order 버그나 잘못된 직접 호출일 때만 생긴다
        // 로그에 적힌 재고를 대신 복원하지 않는 이유: 잠근 건 요청한 재고 행이라, 로그의 재고 행을 건드리는
        // 다른 요청과는 줄을 서지 않는다. 그 행까지 잠그면 행 두 개를 잠가 데드락 위험이 생기고, order 버그도 묻힌다
        Inventory decreasedInventory = decreased.get().getInventory();
        if (!decreasedInventory.getId().equals(locked.getId())) {
            throw new BusinessException(
                    CommonErrorCode.BUSINESS_RULE_VIOLATION,
                    "차감한 재고와 복원 요청의 허브·상품이 다릅니다.",
                    List.of(new ApiErrorDetail("hubId", "차감한 허브는 " + decreasedInventory.getHub().getId() + "입니다."),
                            new ApiErrorDetail("productId", "차감한 상품은 " + decreasedInventory.getProductId() + "입니다.")));
        }

        // 5. 요청 수량이 아니라 실제로 차감한 수량(DECREASE 로그)만큼 되돌린다
        int quantity = decreased.get().getQuantity();
        inventoryRepository.increaseQuantity(locked.getId(), quantity, LocalDateTime.now(), userId);

        // 6. 복원 후 수량으로 다시 조회한다 (UPDATE가 영속성 컨텍스트를 비워 복원 전 엔티티는 남지 않는다)
        Inventory inventory = findInventory(request);
        saveRestoreLog(inventory, orderId, quantity);
        return StockRestoreResponse.of(inventory, quantity, orderId);
    }

    // 유니크 위반이 이 트랜잭션 안에서 나도록 바로 flush 한다
    private void saveRestoreLog(Inventory inventory, UUID orderId, int quantity) {
        inventoryLogRepository.saveAndFlush(InventoryLog.builder()
                .inventory(inventory)
                .orderId(orderId)
                .changeType(InventoryChangeType.RESTORE)
                .quantity(quantity)
                .build());
    }

    // 재고 잠금 조회 (SELECT ... FOR UPDATE)
    private Inventory findInventoryForUpdate(StockRequest request) {
        return inventoryRepository.findByHubIdAndProductIdForUpdate(request.hubId(), request.productId())
                .orElseThrow(HubExceptions::stockNotFound);
    }

    // 재고 조회
    private Inventory findInventory(StockRequest request) {
        return inventoryRepository.findByHubIdAndProductId(request.hubId(), request.productId())
                .orElseThrow(HubExceptions::stockNotFound);
    }

    // 로그 조회(orderId, changeType)
    private Optional<InventoryLog> findOrderLog(UUID orderId, InventoryChangeType changeType) {
        return inventoryLogRepository.findByOrderIdAndChangeType(orderId, changeType);
    }
}
