package com.yuksabal.user.user.service;

import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.feign.FeignClientException;
import com.yuksabal.common.response.ApiErrorDetail;
import com.yuksabal.user.user.client.CompanyClient;
import com.yuksabal.user.user.client.HubClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static com.yuksabal.common.exception.CommonErrorCode.VALIDATION_ERROR;

// 소속 허브·업체 존재 확인 (회원가입, MASTER 회원 수정). null인 값은 확인하지 않는다
// - 없거나 삭제된 허브·업체(내부 API 404) → 400 VALIDATION_ERROR, 둘 다 없으면 둘 다 details로 알려준다
// - 연결 실패·타임아웃·5xx는 확인하지 못한 것이지 없는 것이 아니므로 잡지 않는다 → common FeignExceptionHandler가 502
// - 404가 아닌 4xx도 잡지 않는다 → common이 상태·코드를 그대로 전달
@Component
@RequiredArgsConstructor
public class AffiliationValidator {

    private final HubClient hubClient;
    private final CompanyClient companyClient;

    public void validate(UUID hubId, UUID companyId) {
        List<ApiErrorDetail> details = new ArrayList<>();
        check(hubId, hubClient::getHub, "hubId", "존재하지 않거나 삭제된 허브입니다.", details);
        check(companyId, companyClient::getCompany, "companyId", "존재하지 않거나 삭제된 업체입니다.", details);
        if (!details.isEmpty()) {
            throw new BusinessException(VALIDATION_ERROR, "소속 허브 또는 업체를 찾을 수 없습니다.", details);
        }
    }

    private void check(UUID id, Consumer<UUID> lookup, String field, String reason, List<ApiErrorDetail> details) {
        if (id == null) {
            return;
        }
        try {
            lookup.accept(id);
        } catch (FeignClientException e) {
            if (e.getStatus() != HttpStatus.NOT_FOUND.value()) {
                throw e;
            }
            details.add(new ApiErrorDetail(field, reason));
        }
    }
}
