package com.yuksabal.company.product.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

// 부분 수정: 모든 필드 선택, null이면 기존 값 유지. 길이 제한은 생성 요청과 같다
// 설명은 빈 문자열("")로 보내면 비운다. 그 외 값 지우기(null로 바꾸기)와 소속 업체 변경은 지원하지 않는다
@Schema(description = "상품 수정 요청 (보낸 필드만 수정)")
public record ProductUpdateRequest(
        @Schema(description = "상품명. 생략 시 유지", example = "냉동 고등어 5kg")
        @Size(max = 200, message = "상품명은 200자 이하로 입력해 주세요.")
        @Pattern(regexp = ".*\\S.*", message = "상품명은 공백만 입력할 수 없습니다.")
        String productName,

        @Schema(description = "상품 설명. 생략 시 유지", example = "국내산 냉동 고등어, 5kg 박스")
        @Size(max = 1000, message = "상품 설명은 1000자 이하로 입력해 주세요.")
        String description,

        @Schema(description = "단가 (원 단위 정수, 0 이상). 생략 시 유지", example = "18000")
        @PositiveOrZero(message = "단가는 0 이상이어야 합니다.")
        Long unitPrice
) {
}
