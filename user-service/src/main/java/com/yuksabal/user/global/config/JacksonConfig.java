package com.yuksabal.user.global.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;

// 요청 바디에서 보낸 값이 조용히 무시되거나 덮어써지지 않게 한다 (위반 시 common 핸들러가 400 VALIDATION_ERROR)
// - UUID에 ""·" "를 보내면 Jackson 기본값은 null로 바꾼다 → 수정 API에서 "변경 없음"으로 처리되고,
//   회원가입에서는 소속 없이 가입된다. 빈 문자열은 오류로 처리한다 (null·생략은 그대로 "값 없음")
// - 같은 키가 두 번 오면 Jackson 기본값은 마지막 값만 쓴다. 어느 값을 의도했는지 알 수 없으므로 오류로 처리한다
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer strictRequestBodyCustomizer() {
        return builder -> builder
                .featuresToEnable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .postConfigurer(objectMapper -> objectMapper.coercionConfigFor(UUID.class)
                        .setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail));
    }
}
