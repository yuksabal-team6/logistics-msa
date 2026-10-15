package com.yuksabal.delivery.delivery.service;


import com.yuksabal.common.auth.UserRole;
import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.exception.CommonErrorCode;
import com.yuksabal.common.response.PageResponse;
import com.yuksabal.delivery.delivery.client.HubClient;
import com.yuksabal.delivery.delivery.client.dto.HubRouteResponse;
import com.yuksabal.delivery.delivery.dto.request.DeliveryCreateRequest;
import com.yuksabal.delivery.delivery.dto.request.DeliverySearchRequest;
import com.yuksabal.delivery.delivery.dto.response.DeliveryCreateResponse;
import com.yuksabal.delivery.delivery.dto.response.DeliveryResponse;
import com.yuksabal.delivery.delivery.entity.Delivery;
import com.yuksabal.delivery.delivery.repository.DeliveryRepository;
import com.yuksabal.delivery.routelog.entity.DeliveryRouteLog;
import com.yuksabal.delivery.routelog.repository.DeliveryRouteLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.yuksabal.common.util.PageableUtils.toStablePageable;
import static com.yuksabal.delivery.delivery.repository.DeliverySpecification.search;

@Service
@RequiredArgsConstructor
public class DeliveryService {

    // 목록 정렬 허용 필드 (API 명세 0.4: 정렬 필드는 API별로 제한)
    private static final Set<String> SORTABLE_FIELDS = Set.of("createdAt");

    private final DeliveryRepository deliveryRepository;
    private final DeliveryRouteLogRepository deliveryRouteLogRepository;
    private final HubClient hubClient;
    private final TransactionTemplate transactionTemplate;

    // 내부 API #5. 배송과 경로 기록을 한 트랜잭션으로 저장
    // @Transactional 대신 저장 부분만 transactionTemplate으로 트랜잭션을 연다.
    // 커밋 시점의 유니크 위반을 이 메서드 안에서 잡고, hub 호출을 트랜잭션 밖에 두기 위해서다.
    public DeliveryCreateResponse createDelivery(DeliveryCreateRequest request) {

        // 같은 orderId면 기존 배송 반환 (결정 #18)
        Optional<Delivery> existing = deliveryRepository.findByOrderId(request.orderId());
        if (existing.isPresent()) {
            return DeliveryCreateResponse.from(existing.get());
        }

        // 같은 허브는 자기 자신 경로가 없어 호출하면 404 (결정 #16)
        boolean sameHub = request.departureHubId().equals(request.destinationHubId());
        HubRouteResponse route = sameHub ? null
                : hubClient.getHubRoute(request.departureHubId(), request.destinationHubId()).data();

        try {
            Delivery delivery = transactionTemplate.execute(status -> save(request, route));
            return DeliveryCreateResponse.from(delivery);
        } catch (DataIntegrityViolationException e) {
            // 주문 ID 중복이면 먼저 저장된 배송이 있다 → 그 배송 반환
            // 배송이 없으면 다른 무결성 위반이므로 원래 예외를 그대로 던진다.
            return deliveryRepository.findByOrderId(request.orderId())
                    .map(DeliveryCreateResponse::from)
                    .orElseThrow(()->e);
        }
    }

    // 배송 + 경로 기록 저장. 다른 허브면 경로 기록 1건, 같은 허브면 0건 (결정 #12, #16)
    private Delivery save(DeliveryCreateRequest request, HubRouteResponse route) {
        Delivery delivery = deliveryRepository.save(request.toEntity());
        if (route != null) {
            deliveryRouteLogRepository.save(DeliveryRouteLog.builder()
                    .delivery(delivery)
                    .hubRouteId(route.hubRouteId())
                    .expectedDistanceKm(route.distanceKm())
                    .expectedDurationMinutes(route.durationMinutes())
                    .build());
        }
        return delivery;
    }

    // 내부 API #9. 주문 취소(결정 #19)와 보상(결정 #17)에서 호출된다.
    // 같은 orderId로 여러 번 불려도 결과가 같다.
    @Transactional
    public void cancelDelivery(UUID orderId) {
        Optional<Delivery> found = deliveryRepository.findByOrderId(orderId);
        if (found.isEmpty()) {
            return;   // 배송이 없음: 배송 생성 전에 실패한 보상 → 성공
        }
        Delivery delivery = found.get();
        if (delivery.isCancelled()) {
            return;   // 이미 취소됨: 재시도 → 성공
        }

        // 경로 기록을 먼저 확인한다.
        // 배송이 READY여도 경로 기록이 이미 출발했을 수 있다
        deliveryRouteLogRepository.findByDeliveryId(delivery.getId())
                .ifPresent(DeliveryRouteLog::cancel);
        delivery.cancel();
    }

    // 삭제된 배송은 @SQLRestriction으로 조회되지 않아 없는 배송과 같이 404.
    // DELIVERY는 본인 담당 배송이 아니면 403
    @Transactional(readOnly = true)
    public DeliveryResponse getDelivery(UUID deliveryId, UUID userId, UserRole role) {
        Delivery delivery = deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new BusinessException(
                        CommonErrorCode.RESOURCE_NOT_FOUND,
                        "존재하지 않거나 삭제된 배송입니다.",
                        "deliveryId"));

        if (role == UserRole.DELIVERY && !isManagedBy(delivery, userId)) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        return DeliveryResponse.from(delivery);
    }

    // DELIVERY는 본인 담당 배송만, 그 외 역할은 전체 조회
    @Transactional(readOnly = true)
    public PageResponse<DeliveryResponse> getDeliveries(DeliverySearchRequest request, Pageable pageable,
                                                        UUID userId, UserRole role) {
        Page<Delivery> deliveries = deliveryRepository.findAll(
                search(request, userId, role),
                toStablePageable(pageable, SORTABLE_FIELDS));
        return PageResponse.from(deliveries, DeliveryResponse::from);
    }

    // 배송(허브→업체) 담당자 또는 경로 기록(허브→허브) 담당자가 본인인지 확인한다.
    private boolean isManagedBy(Delivery delivery, UUID userId) {
        if (userId.equals(delivery.getDeliveryManagerId())) {
            return true;
        }
        return deliveryRouteLogRepository.findByDeliveryId(delivery.getId())
                .map(routeLog -> userId.equals(routeLog.getDeliveryManagerId()))
                .orElse(false);
    }

}
