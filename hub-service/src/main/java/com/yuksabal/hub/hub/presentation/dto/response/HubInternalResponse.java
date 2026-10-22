package com.yuksabal.hub.hub.presentation.dto.response;

import com.yuksabal.hub.hub.domain.Hub;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

// company·user-service가 업체 생성·회원가입 시 허브 존재를 확인하는 내부 응답.
// 공개 상세 조회의 HubResponse와 분리해 둔다. 허브 정보가 필요해지면 필드를 추가한다 (호출 측에 영향 없음)
@Schema(description = "허브 내부 응답")
public record HubInternalResponse(
        @Schema(description = "허브 ID", example = "00000000-0000-0000-0000-000000000001")
        UUID hubId
) {

    public static HubInternalResponse from(Hub hub) {
        return new HubInternalResponse(hub.getId());
    }
}
