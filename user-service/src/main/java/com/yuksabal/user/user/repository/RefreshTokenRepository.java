package com.yuksabal.user.user.repository;

import com.yuksabal.user.user.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    // 지운 행 수를 돌려준다. 같은 토큰으로 동시에 재발급하면 먼저 지운 요청만 1, 나머지는 0
    @Modifying
    @Query("delete from RefreshToken r where r.id = :id")
    int deleteOne(@Param("id") UUID id);

    // 로그아웃: 본인 토큰만 지운다
    @Modifying
    @Query("delete from RefreshToken r where r.tokenHash = :tokenHash and r.userId = :userId")
    int deleteByTokenHashAndUserId(@Param("tokenHash") String tokenHash, @Param("userId") UUID userId);

    // 로그인할 때 그 회원의 만료된 토큰을 정리한다 (별도 배치 없이 행이 쌓이지 않게)
    @Modifying
    @Query("delete from RefreshToken r where r.userId = :userId and r.expiresAt <= :now")
    int deleteExpired(@Param("userId") UUID userId, @Param("now") LocalDateTime now);
}
