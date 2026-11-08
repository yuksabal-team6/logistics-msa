package com.yuksabal.user.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

// 발급한 Refresh Token. 원문은 저장하지 않고 SHA-256 해시만 저장한다 (DB가 노출돼도 토큰으로 쓸 수 없다)
// 재발급(rotation)에 쓴 토큰은 지우지 않고 used_at을 남긴다 → 같은 토큰이 다시 오면 탈취로 보고 그 회원 토큰을 전부 폐기한다
// 로그아웃은 행을 지운다. 만료된 행은 다음 로그인 때 정리한다. 한 회원이 여러 기기에서 로그인하면 기기마다 한 행
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "p_refresh_tokens", indexes = @Index(name = "idx_refresh_tokens_user_id", columnList = "user_id"))
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // 재발급에 쓴 시각. null이면 아직 쓰지 않은 토큰
    @Column(name = "used_at")
    private LocalDateTime usedAt;

    private RefreshToken(UUID userId, String tokenHash, LocalDateTime createdAt, LocalDateTime expiresAt) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public static RefreshToken of(UUID userId, String tokenHash, LocalDateTime createdAt, LocalDateTime expiresAt) {
        return new RefreshToken(userId, tokenHash, createdAt, expiresAt);
    }

    public boolean isExpired(LocalDateTime now) {
        return !expiresAt.isAfter(now);
    }

    public boolean isUsed() {
        return usedAt != null;
    }
}
