package com.yuksabal.hub.inventory.service;

import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.exception.CommonErrorCode;
import com.yuksabal.common.response.ApiErrorDetail;
import com.yuksabal.hub.global.exception.HubErrorCode;
import com.yuksabal.hub.inventory.dto.request.StockRequest;
import com.yuksabal.hub.inventory.dto.response.StockDecreaseResponse;
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

    // 같은 주문의 차감이 동시에 들어오면 둘 다 로그 확인을 통과할 수 있다.
    // 늦은 쪽은 DECREASE 로그 INSERT가 유니크 위반으로 실패해 차감까지 롤백되고, 먼저 커밋된 차감 결과로 성공 응답한다
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

        // 1. 이미 차감한 주문이면 변경 없이 성공
        Optional<InventoryLog> decreased = findOrderLog(orderId, InventoryChangeType.DECREASE);
        if (decreased.isPresent()) {
            // 기존 차감 로그에 연결된 재고를 이용해 성공 응답
            return StockDecreaseResponse.of(decreased.get().getInventory(), orderId);
        }

        // 2. 이미 복원(보상)된 주문이면 차감하지 않는다.
        // 차감 요청이 타임아웃된 뒤 order가 복원을 보냈고, 지연된 차감 요청이 그보다 늦게 도착한 경우
        Optional<InventoryLog> restored = findOrderLog(orderId, InventoryChangeType.RESTORE);
        // 이미 복원된 주문이면 늦게 도착한 차감요청이므로 재차감 진행하지 않는다.
        if (restored.isPresent()) {
            return StockDecreaseResponse.of(restored.get().getInventory(), orderId);
        }

        // 엔티티를 먼저 조회하지 않고 조건부 UPDATE부터 실행해, 1차 캐시에 차감 전 수량이 남지 않게 한다.
        int updated = inventoryRepository.decreaseQuantityIfEnough(
                request.hubId(), request.productId(), request.quantity(), LocalDateTime.now(), userId);
        // 1이면 차감 성공, 0이면 차감 실패
        if (updated == 0) {
            return handleNotDecreased(request);
        }

        // UPDATE에 성공한 뒤 해당 재고 엔티티를 조회한다.
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

    // 차감되지 않은 이유: 재고가 없으면 404, 있으면 수량 부족 409
    // 단, 같은 주문의 동시 요청이 먼저 커밋해 수량이 줄어든 경우라면 부족이 아니라 이미 차감된 것이라 성공
    private StockDecreaseResponse handleNotDecreased(StockRequest request) {
        Optional<InventoryLog> decreased = findOrderLog(request.orderId(), InventoryChangeType.DECREASE);
        if (decreased.isPresent()) {
            return StockDecreaseResponse.of(decreased.get().getInventory(), request.orderId());
        }

        Inventory current = findInventory(request);
        throw new BusinessException(
                HubErrorCode.INSUFFICIENT_STOCK,
                HubErrorCode.INSUFFICIENT_STOCK.getMessage(),
                List.of(new ApiErrorDetail("quantity", "현재 재고는 " + current.getQuantity() + "개입니다.")));
    }

    // 재고 조회
    private Inventory findInventory(StockRequest request) {
        return inventoryRepository.findByHubIdAndProductId(request.hubId(), request.productId())
                .orElseThrow(() -> new BusinessException(
                        CommonErrorCode.RESOURCE_NOT_FOUND,
                        "해당 허브에 상품 재고가 없습니다.",
                        "productId"));
    }

    // 로그 조회(orderId, changeType)
    private Optional<InventoryLog> findOrderLog(UUID orderId, InventoryChangeType changeType) {
        return inventoryLogRepository.findByOrderIdAndChangeType(orderId, changeType);
    }
}
