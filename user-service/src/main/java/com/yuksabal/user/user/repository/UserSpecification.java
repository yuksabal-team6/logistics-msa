package com.yuksabal.user.user.repository;

import com.yuksabal.user.user.entity.User;
import com.yuksabal.user.user.entity.UserRole;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.springframework.data.jpa.domain.Specification;

import java.util.UUID;

// 회원 목록 검색 조건. 값이 없으면 null을 반환해 조건에서 빠진다 (company-service CompanySpecification과 같은 방식)
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class UserSpecification {

    public static Specification<User> usernameContains(String username) {
        return (root, query, cb) ->
                username == null || username.isBlank()
                        ? null
                        : cb.like(root.get("username"), "%" + username.trim() + "%");
    }

    public static Specification<User> roleEquals(UserRole role) {
        return (root, query, cb) -> role == null ? null : cb.equal(root.get("role"), role);
    }

    public static Specification<User> hubIdEquals(UUID hubId) {
        return (root, query, cb) -> hubId == null ? null : cb.equal(root.get("hubId"), hubId);
    }
}
