package com.yuksabal.user.user.service;

import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.user.global.config.RefreshTokenProperties;
import com.yuksabal.user.global.exception.UserErrorCode;
import com.yuksabal.user.user.entity.RefreshToken;
import com.yuksabal.user.user.repository.RefreshTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

// Refresh Token 발급·사용(rotation)·폐기. 토큰은 JWT가 아닌 랜덤 문자열이라 게이트웨이에서 Access Token으로 쓸 수 없다
@Service
public class RefreshTokenService {

    private static final int TOKEN_BYTES = 32;

    private final RefreshTokenRepository refreshTokenRepository;
    private final Duration expiration;
    private final SecureRandom secureRandom = new SecureRandom();

    public RefreshTokenService(RefreshTokenRepository refreshTokenRepository, RefreshTokenProperties properties) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.expiration = properties.expiration();
    }

    @Transactional
    public String issue(UUID userId) {
        LocalDateTime now = LocalDateTime.now();
        refreshTokenRepository.deleteExpired(userId, now);
        String token = newToken();
        refreshTokenRepository.save(RefreshToken.of(userId, hash(token), now, now.plus(expiration)));
        return token;
    }

    // 토큰을 폐기하고 주인 회원 ID를 돌려준다 (재발급용, 한 번만 쓸 수 있다)
    // 없음·만료·이미 사용됨(동시 요청 포함)은 모두 같은 401로 응답한다
    // 호출하는 UserService.refresh가 BusinessException에도 커밋하므로, 여기서도 rollback-only로 표시하지 않는다
    @Transactional(noRollbackFor = BusinessException.class)
    public UUID consume(String token) {
        RefreshToken refreshToken = refreshTokenRepository.findByTokenHash(hash(token))
                .filter(found -> !found.isExpired(LocalDateTime.now()))
                .orElseThrow(() -> new BusinessException(UserErrorCode.INVALID_REFRESH_TOKEN));
        if (refreshTokenRepository.deleteOne(refreshToken.getId()) == 0) {
            throw new BusinessException(UserErrorCode.INVALID_REFRESH_TOKEN);
        }
        return refreshToken.getUserId();
    }

    // 로그아웃. 없거나 남의 토큰이어도 결과를 구분하지 않는다
    @Transactional
    public void revoke(UUID userId, String token) {
        refreshTokenRepository.deleteByTokenHashAndUserId(hash(token), userId);
    }

    public long getExpirationSeconds() {
        return expiration.toSeconds();
    }

    private String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
