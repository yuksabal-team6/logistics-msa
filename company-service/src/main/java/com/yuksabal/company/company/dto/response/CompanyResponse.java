package com.yuksabal.company.company.dto.response;

import com.yuksabal.company.company.entity.Company;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.UUID;

// 업체 생성·단건 조회·수정 응답 공용. 내부 API(#2) 응답에는 쓰지 않는다 (다른 서비스와의 계약이라 따로 관리)
@Schema(description = "업체 응답")
public record CompanyResponse(
        @Schema(description = "업체 ID", example = "30000000-0000-4000-8000-000000000001")
        UUID companyId,

        @Schema(description = "업체명", example = "부산 수산물 도매")
        String companyName,

        @Schema(description = "관리 허브 ID", example = "00000000-0000-0000-0000-000000000004")
        UUID hubId,

        @Schema(description = "업체 관리자 ID", example = "10000000-0000-4000-8000-000000000001")
        UUID ownerId,

        @Schema(description = "사업자 등록번호 (선택)", example = "123-45-67890")
        String businessNumber,

        @Schema(description = "업체 주소", example = "부산광역시 중구 중앙대로 1")
        String address,

        @Schema(description = "연락처 (선택)", example = "051-123-4567")
        String contactPhone,

        @Schema(description = "생성일시", example = "2026-10-14T15:30:00")
        LocalDateTime createdAt,

        @Schema(description = "수정일시", example = "2026-10-14T15:30:00")
        LocalDateTime updatedAt
) {

    public static CompanyResponse from(Company company) {
        return new CompanyResponse(
                company.getId(),
                company.getCompanyName(),
                company.getHubId(),
                company.getOwnerId(),
                company.getBusinessNumber(),
                company.getAddress(),
                company.getContactPhone(),
                company.getCreatedAt(),
                company.getUpdatedAt()
        );
    }
}
