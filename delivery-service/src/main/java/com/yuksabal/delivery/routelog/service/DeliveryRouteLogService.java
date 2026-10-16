package com.yuksabal.delivery.routelog.service;

import com.yuksabal.common.auth.UserRole;
import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.exception.CommonErrorCode;
import com.yuksabal.delivery.delivery.entity.Delivery;
import com.yuksabal.delivery.delivery.repository.DeliveryRepository;
import com.yuksabal.delivery.global.exception.DeliveryErrorCode;
import com.yuksabal.delivery.routelog.dto.request.RouteLogStatusUpdateRequest;
import com.yuksabal.delivery.routelog.dto.response.DeliveryRouteLogResponse;
import com.yuksabal.delivery.routelog.entity.DeliveryRouteLog;
import com.yuksabal.delivery.routelog.repository.DeliveryRouteLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DeliveryRouteLogService {

    private final DeliveryRepository deliveryRepository;
    private final DeliveryRouteLogRepository deliveryRouteLogRepository;

    // 배송당 경로 기록은 0~1건이라 페이징 없이 목록으로 응답한다. 같은 허브 배송이면 빈 목록 (결정 #12, #16)
    // 권한은 배송 상세 조회와 같다: DELIVERY는 배송 담당자 또는 경로 기록 담당자만
    @Transactional(readOnly = true)
    public List<DeliveryRouteLogResponse> getRouteLogs(UUID deliveryId, UUID userId, UserRole role) {
        Delivery delivery = findDelivery(deliveryId);
        Optional<DeliveryRouteLog> routeLog = deliveryRouteLogRepository.findByDeliveryId(delivery.getId());

        checkReadable(delivery, routeLog.orElse(null), userId, role);
        return routeLog.stream()
                .map(DeliveryRouteLogResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public DeliveryRouteLogResponse getRouteLog(UUID deliveryId, UUID routeLogId, UUID userId, UserRole role) {
        Delivery delivery = findDelivery(deliveryId);
        DeliveryRouteLog routeLog = findRouteLog(delivery, routeLogId);

        checkReadable(delivery, routeLog, userId, role);
        return DeliveryRouteLogResponse.from(routeLog);
    }

    // 경로 기록(허브→허브) 상태 변경. 404 → 403 → 409 → 400 순서로 확인하고,
    // 전이 규칙과 배송 상태 연동은 DeliveryRouteLog.changeStatus()가 맡는다.
    // 동시 수정은 @Version 충돌로 커밋 시 409 (DeliveryExceptionHandler)
    @Transactional
    public DeliveryRouteLogResponse changeRouteStatus(UUID deliveryId, UUID routeLogId,
                                                      RouteLogStatusUpdateRequest request,
                                                      UUID userId, UserRole role) {
        Delivery delivery = findDelivery(deliveryId);
        DeliveryRouteLog routeLog = findRouteLog(delivery, routeLogId);

        if (!canChangeStatus(routeLog, userId, role)) {
            throw new BusinessException(DeliveryErrorCode.DELIVERY_ACCESS_DENIED, "허브 간 이동 담당자만 변경할 수 있습니다.");
        }
        routeLog.changeStatus(request.routeStatus(), request.actualDistanceKm());
        return DeliveryRouteLogResponse.from(routeLog);
    }

    private Delivery findDelivery(UUID deliveryId) {
        return deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new BusinessException(
                        CommonErrorCode.RESOURCE_NOT_FOUND,
                        "존재하지 않거나 삭제된 배송입니다.",
                        "deliveryId"));
    }

    // 다른 배송의 경로 기록이면 없는 경로 기록과 같이 404. 프록시의 getId()는 추가 조회를 하지 않는다
    private DeliveryRouteLog findRouteLog(Delivery delivery, UUID routeLogId) {
        return deliveryRouteLogRepository.findById(routeLogId)
                .filter(routeLog -> routeLog.getDelivery().getId().equals(delivery.getId()))
                .orElseThrow(() -> new BusinessException(
                        CommonErrorCode.RESOURCE_NOT_FOUND,
                        "존재하지 않거나 삭제된 배송 경로 기록입니다.",
                        "routeLogId"));
    }

    // DeliveryService.isManagedBy와 같은 기준. 경로 기록은 이미 조회했으므로 다시 조회하지 않는다
    private void checkReadable(Delivery delivery, DeliveryRouteLog routeLog, UUID userId, UserRole role) {
        if (role != UserRole.DELIVERY) {
            return;
        }
        boolean managed = userId.equals(delivery.getDeliveryManagerId())
                || (routeLog != null && userId.equals(routeLog.getDeliveryManagerId()));
        if (!managed) {
            throw new BusinessException(DeliveryErrorCode.DELIVERY_ACCESS_DENIED);
        }
    }

    // MASTER는 전체, DELIVERY는 경로 기록(허브→허브) 담당자 본인만. 배송(허브→업체) 담당자는 불가 (배송 상태 변경과 반대)
    private boolean canChangeStatus(DeliveryRouteLog routeLog, UUID userId, UserRole role) {
        return switch (role) {
            case MASTER -> true;
            case DELIVERY -> userId.equals(routeLog.getDeliveryManagerId());
            case USER, COMPANY -> false;
        };
    }
}
