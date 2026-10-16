package com.yuksabal.hub.hub.repository;

import com.yuksabal.common.util.LikeUtils;
import com.yuksabal.hub.hub.entity.Hub;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.springframework.data.jpa.domain.Specification;

// 허브 목록 검색 조건. 값이 없으면 null을 반환해 조건에서 빠진다. 삭제된 허브는 @SQLRestriction으로 제외
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class HubSpecification {

    // 허브명 또는 주소에 검색어가 포함된 허브. 검색어의 % _ 는 와일드카드가 아니라 글자로 비교한다
    public static Specification<Hub> keywordContains(String keyword) {
        return (root, query, cb) -> {
            if (keyword == null || keyword.isBlank()) {
                return null;
            }
            String pattern = LikeUtils.contains(keyword);
            return cb.or(
                    cb.like(root.get("hubName"), pattern, LikeUtils.ESCAPE_CHAR),
                    cb.like(root.get("address"), pattern, LikeUtils.ESCAPE_CHAR));
        };
    }
}
