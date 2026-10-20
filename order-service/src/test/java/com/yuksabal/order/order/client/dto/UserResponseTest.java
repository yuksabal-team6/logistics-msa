package com.yuksabal.order.order.client.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// 내부 API 8 응답을 order가 받는 형태 (#221). Feign과 같은 Spring 기본 ObjectMapper 설정으로 확인
class UserResponseTest {

    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
    private final UUID userId = UUID.randomUUID();

    @Test
    void slackId가_있으면_그대로_받음() throws Exception {
        String json = """
                {"userId":"%s","username":"user01","active":true,"role":"USER","slackId":"U12345678"}
                """.formatted(userId);

        UserResponse response = objectMapper.readValue(json, UserResponse.class);

        assertThat(response.slackId()).isEqualTo("U12345678");
        assertThat(response.active()).isTrue();
    }

    // user가 slackId를 아직 안 주는 버전(#224 배포 전)이어도 깨지지 않고 null → 멘션 없이 알림
    @Test
    void slackId_필드가_없어도_null로_받음() throws Exception {
        String json = """
                {"userId":"%s","username":"user01","active":true,"role":"USER","hubId":null,"companyId":null}
                """.formatted(userId);

        UserResponse response = objectMapper.readValue(json, UserResponse.class);

        assertThat(response.slackId()).isNull();
        assertThat(response.userId()).isEqualTo(userId);
        assertThat(response.active()).isTrue();
    }
}
