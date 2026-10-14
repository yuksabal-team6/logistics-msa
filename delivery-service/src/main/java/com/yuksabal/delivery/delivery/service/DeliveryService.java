package com.yuksabal.delivery.delivery.service;


import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.exception.CommonErrorCode;
import com.yuksabal.delivery.delivery.client.HubClient;
import com.yuksabal.delivery.delivery.client.dto.HubRouteResponse;
import com.yuksabal.delivery.delivery.dto.DeliveryCreateRequest;
import com.yuksabal.delivery.delivery.dto.DeliveryCreateResponse;
import com.yuksabal.delivery.delivery.entity.Delivery;
import com.yuksabal.delivery.delivery.repository.DeliveryRepository;
import com.yuksabal.delivery.routelog.entity.DeliveryRouteLog;
import com.yuksabal.delivery.routelog.repository.DeliveryRouteLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DeliveryService {

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


    // 동시 요청으로 유니크 위반이 났을 때 먼저 저장된 배송을 찾는다.
    private DeliveryCreateResponse getByOrderId(UUID orderId) {
        return deliveryRepository.findByOrderId(orderId)
                .map(DeliveryCreateResponse::from)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND,
                        "존재하지 않거나 삭제된 배송입니다.", "orderId"));
    }
}
