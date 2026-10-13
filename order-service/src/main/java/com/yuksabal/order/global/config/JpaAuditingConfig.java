package com.yuksabal.order.global.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;
import java.util.UUID;

@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {

    // 게이트웨이가 JWT 검증 후 넘겨주는 요청자 ID 헤더
    private static final String USER_ID_HEADER = "X-User-Id";

    // created_by·updated_by를 요청 헤더의 회원 ID로 채운다. 헤더가 없거나(회원가입 등) 형식이 틀리면 null.
    @Bean
    public AuditorAware<UUID> auditorAware() {
        return () -> {
            if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
                return Optional.empty();
            }
            HttpServletRequest request = attributes.getRequest();
            String userId = request.getHeader(USER_ID_HEADER);
            if (userId == null) {
                return Optional.empty();
            }
            try {
                return Optional.of(UUID.fromString(userId));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        };
    }
}
