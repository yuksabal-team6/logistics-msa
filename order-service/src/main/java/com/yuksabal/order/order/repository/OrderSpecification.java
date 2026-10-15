package com.yuksabal.order.order.repository;

import com.yuksabal.order.order.entity.Order;
import com.yuksabal.order.order.entity.OrderStatus;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 주문 목록 조회를 위한 동적 검색 조건을 정의한다.
 * <p>
 * 검색 조건을 동적으로 조합하기 위해 Specification을 사용한다.
 * 검색값이 null 이면 해당 조건을 적용하지 않는다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class OrderSpecification {

    /**
     * 주문자 ID가 일치하는 검색 조건을 생성한다.
     *
     * @param ordererId 검색할 주문자 ID
     * @return 주문자 ID 일치 조건 Specification 객체
     */
    public static Specification<Order> ordererIdEquals(UUID ordererId) {
        return (root, query, cb) ->
                ordererId == null
                        ? null
                        : cb.equal(root.get("ordererId"), ordererId);
    }

    /**
     * 요청 업체 ID가 일치하는 검색 조건을 생성한다.
     *
     * @param requestCompanyId 검색할 업체 ID
     * @return 요청 업체 ID 일치 조건
     */
    public static Specification<Order> requestCompanyIdEquals(UUID requestCompanyId) {
        return (root, query, cb) ->
                requestCompanyId == null
                        ? null
                        : cb.equal(root.get("requestCompanyId"), requestCompanyId);
    }

    /**
     * 공급 업체 ID가 일치하는 검색 조건을 생성한다.
     *
     * @param supplierCompanyId 검색할 업체 ID
     * @return 공급 업체 ID 일치 조건
     */
    public static Specification<Order> supplierCompanyIdEquals(UUID supplierCompanyId) {
        return (root, query, cb) ->
                supplierCompanyId == null
                        ? null
                        : cb.equal(root.get("supplierCompanyId"), supplierCompanyId);
    }

    /**
     * 상품 ID가 일치하는 검색 조건을 생성한다.
     *
     * @param productId 검색할 상품 ID
     * @return 상품 ID 일치 조건
     */
    public static Specification<Order> productIdEquals(UUID productId) {
        return (root, query, cb) ->
                productId == null
                        ? null
                        : cb.equal(root.get("productId"), productId);
    }

    /**
     * 주문의 상태가 일치하는 검색 조건을 생성한다.
     *
     * @param status 검색할 상태
     * @return status 일치 조건
     */
    public static Specification<Order> statusEquals(OrderStatus status) {
        return (root, query, cb) ->
                status == null
                        ? null
                        : cb.equal(root.get("status"), status);
    }

    /**
     * 시작일의 00:00:00 이상인 조건을 생성한다.
     *
     * @param from 검색 시작일
     * @return 시작일 이상 조건
     */
    public static Specification<Order> createdFrom(LocalDate from) {
        return (root, query, cb) ->
                from == null
                        ? null
                        : cb.greaterThanOrEqualTo(root.get("createdAt"), from.atStartOfDay());
    }

    /**
     * 종료일 포함 다음날 00:00:00 미만인 조건을 생성한다.
     *
     * @param to 검색 종료일
     * @return 종료일 다음 날 00:00:00 미만 조건
     */
    public static Specification<Order> createdTo(LocalDate to) {
        return (root, query, cb) ->
                to == null
                        ? null
                        : cb.lessThan(root.get("createdAt"), to.plusDays(1).atStartOfDay());
    }
}
