package com.yuksabal.user.user.repository;

import com.yuksabal.user.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

// User의 @SQLRestriction으로 삭제된 회원은 조회·중복 확인에서 제외된다
public interface UserRepository extends JpaRepository<User, UUID> {

    // 삭제된 회원은 조회되지 않으므로 로그인할 수 없다
    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);
}
