package com.yuksabal.user.user.service;

import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.user.user.client.DeliveryClient;
import com.yuksabal.user.user.client.dto.DeliveryInProgressResponse;
import com.yuksabal.user.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.yuksabal.common.exception.CommonErrorCode.DEPENDENCY_FAILURE;

// #68 진행 중 배송 확인. 역할이 바뀌어도 기존 배송 건에 담당자로 남을 수 있어 역할과 관계없이 확인한다 (delivery 답변)
@Component
@RequiredArgsConstructor
public class DeliveryActiveWorkChecker implements ActiveWorkChecker {

    private final DeliveryClient deliveryClient;

    @Override
    public Target target() {
        return Target.DELIVERY;
    }

    @Override
    public List<ActiveWork> findActiveWork(User user) {
        ApiResponse<DeliveryInProgressResponse> response = deliveryClient.getInProgressDelivery(user.getId());
        if (response == null || response.data() == null) {
            // 응답이 비어 있으면 확인하지 못한 것으로 보고 막는다
            throw new BusinessException(DEPENDENCY_FAILURE);
        }
        return response.data().hasInProgressDelivery()
                ? List.of(new ActiveWork("delivery", "진행 중인 배송이 있습니다."))
                : List.of();
    }
}
