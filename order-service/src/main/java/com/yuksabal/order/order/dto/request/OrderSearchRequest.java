package com.yuksabal.order.order.dto.request;

import com.yuksabal.order.order.entity.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.UUID;

// 주문 검색 조건 (명세 v1 9장). 모두 선택, 생략 시 조건 없음
// 기간은 yyyy-MM-dd, 시작일, 종료일 모두 포함: createdFrom 00:00 이상 ~ createdTo 다음 날 00:00 미만
// 날짜 기준은 한국 시간 (팀 설정 hibernate.jdbc.time_zone, Docker TZ 모두 Asia/Seoul)
@Schema(description = "주문 검색 요청")
public record OrderSearchRequest(
        @Schema(description = "요청 업체 ID", example = "30000000-0000-4000-8000-000000000002")
        UUID requestCompanyId,

        @Schema(description = "공급 업체 ID", example = "30000000-0000-4000-8000-000000000001")
        UUID supplierCompanyId,

        @Schema(description = "상품 ID", example = "40000000-0000-4000-8000-000000000001")
        UUID productId,

        @Schema(description = "주문 상태", example = "CONFIRMED")
        OrderStatus status,

        @Schema(description = "생성일 시작 (yyyy-MM-dd, 포함, 한국 시간)", example = "2026-10-01")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate createdFrom,

        @Schema(description = "생성일 끝 (yyyy-MM-dd, 포함, 한국 시간)", example = "2026-10-31")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate createdTo
) {
}
