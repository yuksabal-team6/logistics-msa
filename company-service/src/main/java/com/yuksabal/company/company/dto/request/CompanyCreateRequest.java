package com.yuksabal.company.company.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

// 길이 제한은 p_companies 컬럼 길이와 맞춘다. 컬럼을 바꾸면 여기도 같이 수정
@Schema(description = "업체 생성 요청")
public record CompanyCreateRequest(
        @Schema(description = "업체명", example = "부산 수산물 도매")
        @NotBlank(message = "업체명은 필수입니다.")
        @Size(max = 200, message = "업체명은 200자 이하로 입력해 주세요.")
        String companyName,

        // 존재 여부는 서비스에서 hub-service에 확인 (Feign)
        @Schema(description = "관리 허브 ID", example = "00000000-0000-0000-0000-000000000004")
        @NotNull(message = "관리 허브 ID는 필수입니다.")
        UUID hubId,

        // MASTER는 지정할 관리자 ID, COMPANY는 본인 ID여야 한다 (서비스에서 X-User-Id와 비교)
        @Schema(description = "업체 관리자 회원 ID", example = "10000000-0000-4000-8000-000000000001")
        @NotNull(message = "업체 관리자 ID는 필수입니다.")
        UUID ownerId,

        @Schema(description = "사업자 등록번호 (선택)", example = "123-45-67890")
        @Pattern(regexp = "^\\d{3}-\\d{2}-\\d{5}$", message = "사업자 등록번호는 000-00-00000 형식으로 입력해 주세요.")
        String businessNumber,

        @Schema(description = "업체 주소", example = "부산광역시 중구 중앙대로 1")
        @NotBlank(message = "업체 주소는 필수입니다.")
        @Size(max = 500, message = "업체 주소는 500자 이하로 입력해 주세요.")
        String address,

        @Schema(description = "연락처 (선택)", example = "051-123-4567")
        @Size(max = 30, message = "연락처는 30자 이하로 입력해 주세요.")
        String contactPhone
) {
}
