package com.yuksabal.company.company.dto.response;

import com.yuksabal.company.company.entity.Company;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

// 내부 API #2 응답. 다른 서비스(order 등)와의 계약이라 필드 이름·구성을 바꾸면 호출하는 쪽 DTO도 같이 수정해야 한다
// 외부 응답(CompanyResponse)과 분리해 외부 API 변경이 내부 계약에 번지지 않게 한다
@Schema(description = "업체 정보 (내부 API)")
public record CompanyInternalResponse(
        @Schema(description = "업체 ID", example = "30000000-0000-4000-8000-000000000001")
        UUID companyId,

        @Schema(description = "업체명", example = "부산 수산물 도매")
        String companyName,

        @Schema(description = "관리 허브 ID", example = "00000000-0000-0000-0000-000000000004")
        UUID hubId,

        @Schema(description = "업체 주소", example = "부산광역시 중구 중앙대로 1")
        String address,

        @Schema(description = "업체 관리자 회원 ID", example = "10000000-0000-4000-8000-000000000001")
        UUID ownerId,

        @Schema(description = "활성 여부. false면 새 주문(공급·요청 업체)을 받지 않는다", example = "true")
        boolean active
) {

    public static CompanyInternalResponse from(Company company) {
        return new CompanyInternalResponse(
                company.getId(),
                company.getCompanyName(),
                company.getHubId(),
                company.getAddress(),
                company.getOwnerId(),
                company.isActive()
        );
    }
}
