package com.yuksabal.user.user.service;

import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.user.user.client.OrderClient;
import com.yuksabal.user.user.client.dto.OrderInProgressResponse;
import com.yuksabal.user.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.yuksabal.common.exception.CommonErrorCode.DEPENDENCY_FAILURE;

// #68 진행 중 주문 확인. 모든 역할이 주문할 수 있으므로 역할과 관계없이 확인한다
@Component
@RequiredArgsConstructor
public class OrderActiveWorkChecker implements ActiveWorkChecker {

    private final OrderClient orderClient;

    @Override
    public Target target() {
        return Target.ORDER;
    }

    @Override
    public List<ActiveWork> findActiveWork(User user) {
        ApiResponse<OrderInProgressResponse> response = orderClient.getInProgressOrder(user.getId());
        if (response == null || response.data() == null) {
            // 응답이 비어 있으면 확인하지 못한 것으로 보고 막는다
            throw new BusinessException(DEPENDENCY_FAILURE);
        }
        return response.data().hasInProgressOrder()
                ? List.of(new ActiveWork("order", "진행 중인 주문이 있습니다."))
                : List.of();
    }
}
