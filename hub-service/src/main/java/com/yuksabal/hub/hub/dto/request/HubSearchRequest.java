package com.yuksabal.hub.hub.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

@Schema(description = "허브 검색 조건")
public record HubSearchRequest(
        @Size(max = 100, message = "검색어는 100자 이하여야 합니다.")
        @Schema(description = "검색어 (허브명 또는 주소에 포함). 생략 시 전체 조회", example = "서울")
        String keyword
) {
}
