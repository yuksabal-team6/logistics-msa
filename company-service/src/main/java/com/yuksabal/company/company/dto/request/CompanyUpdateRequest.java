package com.yuksabal.company.company.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

// 부분 수정: 모든 필드 선택, null이면 기존 값 유지. 길이 제한은 p_companies 컬럼 길이와 맞춘다
// 값을 지우는(null로 바꾸는) 기능은 지원하지 않는다
@Schema(description = "업체 수정 요청 (보낸 필드만 수정)")
public record CompanyUpdateRequest(
        @Schema(description = "업체명. 생략 시 유지", example = "부산 수산물 도매 본점")
        @Size(max = 200, message = "업체명은 200자 이하로 입력해 주세요.")
        @Pattern(regexp = ".*\\S.*", message = "업체명은 공백만 입력할 수 없습니다.")
        String companyName,

        @Schema(description = "관리 허브 ID. MASTER만 변경 가능, 생략 시 유지", example = "00000000-0000-0000-0000-000000000004")
        UUID hubId,

        @Schema(description = "업체 관리자 회원 ID. MASTER만 변경 가능, 생략 시 유지", example = "10000000-0000-4000-8000-000000000001")
        UUID ownerId,

        @Schema(description = "사업자 등록번호. 생략 시 유지", example = "123-45-67890")
        @Pattern(regexp = "^\\d{3}-\\d{2}-\\d{5}$", message = "사업자 등록번호는 000-00-00000 형식으로 입력해 주세요.")
        String businessNumber,

        @Schema(description = "업체 주소. 생략 시 유지", example = "부산광역시 중구 중앙대로 1")
        @Size(max = 500, message = "업체 주소는 500자 이하로 입력해 주세요.")
        @Pattern(regexp = ".*\\S.*", message = "업체 주소는 공백만 입력할 수 없습니다.")
        String address,

        @Schema(description = "연락처. 생략 시 유지", example = "051-123-4567")
        @Size(max = 30, message = "연락처는 30자 이하로 입력해 주세요.")
        String contactPhone
) {
}
