package com.yuksabal.delivery.delivery.application;

import com.yuksabal.common.auth.UserRole;
import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.feign.FeignClientException;
import com.yuksabal.delivery.delivery.infrastructure.UserClient;
import com.yuksabal.delivery.delivery.infrastructure.dto.UserResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.UUID;

import static com.yuksabal.common.exception.CommonErrorCode.BUSINESS_RULE_VIOLATION;
import static com.yuksabal.common.exception.CommonErrorCode.RESOURCE_NOT_FOUND;

// 배송·경로 기록 담당자 지정에서 같이 쓰는 회원 검증. 두 서비스가 같은 메시지·필드로 응답하도록 한 곳에 둔다
// 트랜잭션 밖에서 호출한다 (user 응답을 기다리는 동안 DB 커넥션을 잡지 않도록)
@Component
@RequiredArgsConstructor
public class DeliveryManagerValidator {

    private static final String FIELD = "deliveryManagerId";

    private final UserClient userClient;

    // 지정할 회원이 활성 DELIVERY인지 확인한다. hubId는 확인하지 않는다 (배송 담당자는 공간 제약 없음)
    // user 5xx·타임아웃은 잡지 않아 common Feign 처리로 502
    public void validate(UUID deliveryManagerId) {
        UserResponse user = getUser(deliveryManagerId);

        if (!user.active()) {
            throw ruleViolation("비활성화된 회원은 배송 담당자로 지정할 수 없습니다.");
        }
        // UserRole.from()은 요청자 헤더용이라 모르는 값이면 403을 던진다. 여기선 어떤 값이든 DELIVERY가 아니면 422
        if (!UserRole.DELIVERY.name().equals(user.role())) {
            throw ruleViolation("배송 담당자는 DELIVERY 역할 회원만 지정할 수 있습니다.");
        }
    }

    // user의 404는 field가 userId라 요청 필드(deliveryManagerId)로 바꿔 던진다. 다른 4xx는 그대로 전달
    private UserResponse getUser(UUID deliveryManagerId) {
        try {
            return userClient.getUser(deliveryManagerId).data();
        } catch (FeignClientException e) {
            if (e.getStatus() == HttpStatus.NOT_FOUND.value()) {
                throw new BusinessException(RESOURCE_NOT_FOUND, "존재하지 않거나 탈퇴한 회원입니다.", FIELD);
            }
            throw e;
        }
    }

    private BusinessException ruleViolation(String message) {
        return new BusinessException(BUSINESS_RULE_VIOLATION, message, FIELD);
    }
}