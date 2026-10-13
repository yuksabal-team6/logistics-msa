package com.yuksabal.company.test.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

// [세팅 검증용]
@Schema(description = "테스트 업체 생성 요청")
public record TestCompanyCreateRequest(
        @Schema(description = "업체명", example = "테스트 수산")
        @NotBlank(message = "업체명은 필수입니다.")
        String companyName,

        @Schema(description = "관리 허브 ID (초기 데이터: 서울 센터)", example = "00000000-0000-0000-0000-000000000001")
        @NotNull(message = "허브 ID는 필수입니다.")
        UUID hubId,

        @Schema(description = "업체 관리자 회원 ID", example = "11111111-1111-1111-1111-111111111111")
        @NotNull(message = "업체 관리자 ID는 필수입니다.")
        UUID ownerId,

        @Schema(description = "업체 주소", example = "부산 동구 중앙대로 1")
        @NotBlank(message = "주소는 필수입니다.")
        String address
) {
}
