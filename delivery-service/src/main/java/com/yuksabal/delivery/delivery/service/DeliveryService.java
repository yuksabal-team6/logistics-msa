package com.yuksabal.delivery.delivery.service;


import com.yuksabal.common.auth.UserRole;
import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.exception.CommonErrorCode;
import com.yuksabal.common.feign.FeignClientException;
import com.yuksabal.common.feign.FeignServerException;
import com.yuksabal.common.response.PageResponse;
import com.yuksabal.delivery.delivery.client.HubClient;
import com.yuksabal.delivery.delivery.client.OrderClient;
import com.yuksabal.delivery.delivery.client.dto.HubRouteResponse;
import com.yuksabal.delivery.delivery.dto.request.DeliveryCreateRequest;
import com.yuksabal.delivery.delivery.dto.request.DeliverySearchRequest;
import com.yuksabal.delivery.delivery.dto.response.DeliveryCreateResponse;
import com.yuksabal.delivery.delivery.dto.response.DeliveryResponse;
import com.yuksabal.delivery.delivery.entity.Delivery;
import com.yuksabal.delivery.delivery.entity.DeliveryStatus;
import com.yuksabal.delivery.delivery.repository.DeliveryRepository;
import com.yuksabal.delivery.global.exception.DeliveryErrorCode;
import com.yuksabal.delivery.routelog.entity.DeliveryRouteLog;
import com.yuksabal.delivery.routelog.repository.DeliveryRouteLogRepository;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryService {

    // 목록 정렬 허용 필드 (API 명세 0.4: 정렬 필드는 API별로 제한)
    private static final Set<String> SORTABLE_FIELDS = Set.of("createdAt");

    private final DeliveryRepository deliveryRepository;
    private final DeliveryRouteLogRepository deliveryRouteLogRepository;
    private final HubClient hubClient;
    private final OrderClient orderClient;
    private final DeliveryManagerValidator deliveryManagerValidator;
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
        Delivery delivery = findDelivery(deliveryId);

        if (role == UserRole.DELIVERY && !isManagedBy(delivery, userId)) {
            throw new BusinessException(DeliveryErrorCode.DELIVERY_ACCESS_DENIED);
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

    // 배송(허브→업체) 상태 변경. 404 → 403 → 409 순서로 확인하고, 전이 규칙은 Delivery.changeStatus()가 맡는다.
    // 저장만 transactionTemplate으로 커밋하고, COMPLETED면 커밋 후 트랜잭션 밖에서 order에 알린다 (createDelivery와 같은 이유).
    // 동시 수정은 @Version 충돌로 커밋 시 execute()에서 예외가 나가 order를 호출하지 않고 409 (DeliveryExceptionHandler)
    public DeliveryResponse changeDeliveryStatus(UUID deliveryId, DeliveryStatus target, UUID userId, UserRole role) {
        DeliveryResponse response = transactionTemplate.execute(status -> {
            Delivery delivery = findDelivery(deliveryId);

            if (!canChangeStatus(delivery, userId, role)) {
                throw new BusinessException(DeliveryErrorCode.DELIVERY_ACCESS_DENIED, "업체 배송 담당자만 변경할 수 있습니다.");
            }
            delivery.changeStatus(target);
            return DeliveryResponse.from(delivery);
        });

        if (response.deliveryStatus() == DeliveryStatus.COMPLETED) {
            notifyOrderCompleted(response.orderId(), response.deliveryId());
        }
        return response;
    }

    // 내부 API #10. 배송은 이미 커밋됐으므로 order 실패는 배송 API 실패로 만들지 않고 로그만 남긴다.
    // 배송 COMPLETED · 주문 CONFIRMED로 어긋난 채 남으므로 [수동 처리 필요]로 표시한다. 재시도·재처리는 별도 이슈
    // try 안은 Feign 호출뿐이라 DB 예외가 섞이지 않는다
    private void notifyOrderCompleted(UUID orderId, UUID deliveryId) {
        try {
            orderClient.completeOrder(orderId);
        } catch (FeignClientException e) {
            // 404 주문 없음, 409 CONFIRMED가 아닌 주문
            log.warn("[수동 처리 필요] 주문 완료 처리 실패 orderId={}, deliveryId={}, status={}, code={}, message={}",
                    orderId, deliveryId, e.getStatus(), e.getCode(), e.getMessage());
        } catch (FeignServerException e) {
            // order 5xx, Eureka에 order 인스턴스 없음(LoadBalancer 503)
            log.error("[수동 처리 필요] 주문 완료 처리 실패 orderId={}, deliveryId={}, status={}, code={}, message={}",
                    orderId, deliveryId, e.getStatus(), e.getCode(), e.getMessage());
        } catch (FeignException e) {
            // 타임아웃·연결 실패(RetryableException), 응답 역직렬화 실패
            log.error("[수동 처리 필요] 주문 완료 처리 실패 orderId={}, deliveryId={}, cause={}",
                    orderId, deliveryId, e.getMessage());
        } catch (RuntimeException e) {
            // 안전망: 예상 못 한 예외. 배송은 이미 커밋됐으므로 500을 내지 않는다
            log.error("[수동 처리 필요] 주문 완료 처리 실패 orderId={}, deliveryId={}",
                    orderId, deliveryId, e);
        }
    }

    // 배송(허브→업체) 담당자 지정. 403 → 404 → 409 → user 확인(404·422) 순서
    // 1차 확인으로 담당자를 지정할 수 없는 배송(업체로 출발했거나 끝남)이면 user를 부르지 않고, user 호출은 트랜잭션 밖에서 한다 (createDelivery와 같은 이유)
    // 저장 트랜잭션에서 상태를 다시 확인해 그 사이에 지정할 수 없게 된 배송은 409, 동시 수정은 @Version 충돌로 409
    public DeliveryResponse assignDeliveryManager(UUID deliveryId, UUID deliveryManagerId, UserRole role) {
        requireMaster(role);
        transactionTemplate.executeWithoutResult(status -> findDelivery(deliveryId).checkManagerAssignable());

        deliveryManagerValidator.validate(deliveryManagerId);

        return transactionTemplate.execute(status -> {
            Delivery delivery = findDelivery(deliveryId);
            delivery.assignManager(deliveryManagerId);
            return DeliveryResponse.from(delivery);
        });
    }

    private void requireMaster(UserRole role) {
        if (role != UserRole.MASTER) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN, "MASTER만 담당자를 지정할 수 있습니다.");
        }
    }

    private Delivery findDelivery(UUID deliveryId) {
        return deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new BusinessException(
                        CommonErrorCode.RESOURCE_NOT_FOUND,
                        "존재하지 않거나 삭제된 배송입니다.",
                        "deliveryId"));
    }

    // MASTER는 전체, DELIVERY는 배송(허브→업체) 담당자 본인만. 경로 기록 담당자는 불가라 조회용 isManagedBy를 쓰지 않는다
    private boolean canChangeStatus(Delivery delivery, UUID userId, UserRole role) {
        return switch (role) {
            case MASTER -> true;
            case DELIVERY -> userId.equals(delivery.getDeliveryManagerId());
            case USER, COMPANY -> false;
        };
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
