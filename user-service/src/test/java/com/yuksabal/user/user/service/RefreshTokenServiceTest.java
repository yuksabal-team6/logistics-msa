package com.yuksabal.user.user.service;

import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.user.global.config.RefreshTokenProperties;
import com.yuksabal.user.global.exception.UserErrorCode;
import com.yuksabal.user.user.entity.RefreshToken;
import com.yuksabal.user.user.repository.RefreshTokenRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class RefreshTokenServiceTest {

    private static final Duration SEVEN_DAYS = Duration.ofDays(7);

    private final RefreshTokenRepository repository = mock(RefreshTokenRepository.class);
    private final RefreshTokenService service =
            new RefreshTokenService(repository, new RefreshTokenProperties(SEVEN_DAYS));
    private final UUID userId = UUID.randomUUID();

    @Test
    void 발급하면_원문이_아닌_해시를_7일_만료로_저장한다() {
        // when
        String token = service.issue(userId);

        // then
        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(userId);
        assertThat(saved.getValue().getTokenHash()).isEqualTo(RefreshTokenService.hash(token)).isNotEqualTo(token);
        assertThat(Duration.between(saved.getValue().getCreatedAt(), saved.getValue().getExpiresAt())).isEqualTo(SEVEN_DAYS);
        verify(repository).deleteExpired(eq(userId), any(LocalDateTime.class));
    }

    @Test
    void 발급할_때마다_다른_토큰을_만든다() {
        assertThat(service.issue(userId)).isNotEqualTo(service.issue(userId));
    }

    @Test
    void 유효한_토큰을_쓰면_사용_처리하고_회원_ID를_돌려준다() {
        // given
        RefreshToken stored = stored("token", LocalDateTime.now().plusDays(1));
        given(repository.findByTokenHash(RefreshTokenService.hash("token"))).willReturn(Optional.of(stored));
        given(repository.markUsed(eq(stored.getId()), any())).willReturn(1);

        // when & then
        assertThat(service.consume("token")).isEqualTo(userId);
        verify(repository).markUsed(eq(stored.getId()), any());
        verify(repository, never()).deleteAllByUserId(any());
    }

    @Test
    void 없는_토큰은_401() {
        given(repository.findByTokenHash(any())).willReturn(Optional.empty());

        expectInvalid("unknown");
    }

    @Test
    void 만료된_토큰은_401이고_폐기하지_않는다() {
        // given
        RefreshToken stored = stored("token", LocalDateTime.now().minusSeconds(1));
        given(repository.findByTokenHash(RefreshTokenService.hash("token"))).willReturn(Optional.of(stored));

        // when & then
        expectInvalid("token");
        verify(repository, never()).markUsed(any(), any());
        verify(repository, never()).deleteAllByUserId(any());
    }

    @Test
    void 이미_쓴_토큰이_다시_오면_그_회원의_토큰을_전부_폐기하고_401() {
        // given
        RefreshToken stored = stored("token", LocalDateTime.now().plusDays(1));
        ReflectionTestUtils.setField(stored, "usedAt", LocalDateTime.now().minusMinutes(1));
        given(repository.findByTokenHash(RefreshTokenService.hash("token"))).willReturn(Optional.of(stored));

        // when & then
        expectInvalid("token");
        verify(repository, never()).markUsed(any(), any());
        verify(repository).deleteAllByUserId(userId);
    }

    @Test
    void 동시에_같은_토큰을_쓴_다른_요청이_먼저_사용했으면_재사용으로_보고_전부_폐기하고_401() {
        // given: 조회 때는 미사용이었지만 사용 처리된 행이 없다
        RefreshToken stored = stored("token", LocalDateTime.now().plusDays(1));
        given(repository.findByTokenHash(RefreshTokenService.hash("token"))).willReturn(Optional.of(stored));
        given(repository.markUsed(eq(stored.getId()), any())).willReturn(0);

        // when & then
        expectInvalid("token");
        verify(repository).deleteAllByUserId(userId);
    }

    @Test
    void 로그아웃은_본인_토큰만_지운다() {
        // when
        service.revoke(userId, "token");

        // then
        verify(repository).deleteByTokenHashAndUserId(RefreshTokenService.hash("token"), userId);
    }

    private RefreshToken stored(String token, LocalDateTime expiresAt) {
        RefreshToken refreshToken = RefreshToken.of(userId, RefreshTokenService.hash(token), LocalDateTime.now(), expiresAt);
        ReflectionTestUtils.setField(refreshToken, "id", UUID.randomUUID());
        return refreshToken;
    }

    private void expectInvalid(String token) {
        assertThatThrownBy(() -> service.consume(token))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(UserErrorCode.INVALID_REFRESH_TOKEN);
    }
}
