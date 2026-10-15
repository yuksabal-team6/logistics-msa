package com.yuksabal.company.global.util;

import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.exception.CommonErrorCode;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Set;

// 목록 조회 공통 페이징 처리 (업체·상품 공용, hub-service의 PageableUtils와 같은 모양)
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class PageableUtils {

    // 허용하지 않은 정렬 필드는 400 (API 명세 0.4: 정렬 필드는 API별로 제한)
    // 정렬 값이 같은 행의 순서가 페이지마다 바뀌지 않게 id를 마지막 기준으로 붙인다
    public static Pageable toStablePageable(Pageable pageable, Set<String> sortableFields) {
        for (Sort.Order order : pageable.getSort()) {
            if (!sortableFields.contains(order.getProperty())) {
                throw new BusinessException(
                        CommonErrorCode.VALIDATION_ERROR,
                        "정렬할 수 없는 필드입니다: " + order.getProperty(),
                        "sort");
            }
        }
        return PageRequest.of(
                pageable.getPageNumber(),
                pageable.getPageSize(),
                pageable.getSort().and(Sort.by("id")));
    }
}
