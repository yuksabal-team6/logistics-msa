package com.yuksabal.common.response;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

// 공통 목록 응답. Page를 그대로 반환하지 않고 이 형식으로 변환
// 기본값 page=0, size=20(최대 100), 정렬 createdAt DESC는 CommonWebAutoConfiguration에서 설정한다.
@Schema(description = "목록 응답")
public record PageResponse<T>(
        @Schema(description = "목록")
        List<T> items,
        @Schema(description = "현재 페이지 (0부터 시작)", example = "0")
        int page,
        @Schema(description = "페이지 크기", example = "20")
        int size,
        @Schema(description = "전체 개수", example = "0")
        long totalElements,
        @Schema(description = "전체 페이지 수", example = "0")
        int totalPages
) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }

    // 엔티티 Page를 DTO 목록으로 바꿀 때 사용: PageResponse.from(orderPage, OrderResponse::from)
    public static <E, T> PageResponse<T> from(Page<E> page, Function<E, T> mapper) {
        return from(page.map(mapper));
    }
}
