package com.yuksabal.company.company.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

import java.util.UUID;

@Schema(description = "업체 검색 요청")
public record CompanySearchRequest(
        @Schema(description = "업체명 (일부만 입력해도 검색). 생략 시 조건 없음", example = "수산물")
        @Size(max = 200, message = "업체명은 200자 이하로 입력해 주세요.")
        String companyName,

        @Schema(description = "관리 허브 ID (정확히 일치). 생략 시 조건 없음", example = "00000000-0000-0000-0000-000000000004")
        UUID hubId
) {
}
