package com.yuksabal.user.global.config;

import com.yuksabal.common.exception.ErrorCode;
import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.user.global.swagger.ApiErrorCodes;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// Swagger UI: http://localhost:{server.port}/swagger-ui/index.html
@Configuration
public class SwaggerConfig {

    private static final String JWT_SCHEME = "JWT";
    private static final String ERROR_SCHEMA_REF = "#/components/schemas/ApiResponse";

    @Bean
    public OpenAPI openAPI() {
        // Authorize 버튼에 토큰만 입력하면 "Bearer "를 붙여 Authorization 헤더로 전송
        SecurityScheme bearerScheme = new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT");

        Components components = new Components().addSecuritySchemes(JWT_SCHEME, bearerScheme);
        // 에러 응답 예시에서 참조할 수 있도록 ApiResponse 스키마를 등록
        ModelConverters.getInstance().read(ApiResponse.class).forEach(components::addSchemas);

        // 서버 주소를 상대경로로 두면, 게이트웨이 통합 Swagger에서는 게이트웨이(8080)로,
        // 서비스 Swagger에서는 서비스 포트로 Try it out 요청이 간다
        return new OpenAPI()
                .servers(List.of(new Server().url("/")))
                .info(new Info().title("User Service API").version("v1"))
                .components(components)
                .addSecurityItem(new SecurityRequirement().addList(JWT_SCHEME));
    }

    // @ApiErrorCodes에 적은 공통·도메인 ErrorCode를 상태 코드별로 묶어 응답 예시로 추가
    @Bean
    public OperationCustomizer errorCodeCustomizer() {
        return (operation, handlerMethod) -> {
            ApiErrorCodes apiErrorCodes = handlerMethod.getMethodAnnotation(ApiErrorCodes.class);
            if (apiErrorCodes == null) {
                return operation;
            }

            if (operation.getResponses() == null) {
                operation.setResponses(new ApiResponses());
            }

            Map<Integer, List<ErrorCode>> byStatus = Stream.concat(
                            Stream.<ErrorCode>of(apiErrorCodes.common()),
                            Stream.<ErrorCode>of(apiErrorCodes.value()))
                    .collect(Collectors.groupingBy(errorCode -> errorCode.getStatus().value()));

            byStatus.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                    .forEach(entry -> operation.getResponses()
                            .addApiResponse(String.valueOf(entry.getKey()), toApiResponse(entry.getValue())));

            return operation;
        };
    }

    private io.swagger.v3.oas.models.responses.ApiResponse toApiResponse(List<ErrorCode> errorCodes) {
        MediaType mediaType = new MediaType().schema(new Schema<>().$ref(ERROR_SCHEMA_REF));
        errorCodes.forEach(errorCode -> mediaType.addExamples(errorCode.name(), new Example()
                .summary(errorCode.getMessage())
                .value(ApiResponse.fail(errorCode))));

        String description = errorCodes.stream()
                .map(ErrorCode::getMessage)
                .collect(Collectors.joining(" / "));

        return new io.swagger.v3.oas.models.responses.ApiResponse()
                .description(description)
                .content(new Content().addMediaType(org.springframework.http.MediaType.APPLICATION_JSON_VALUE, mediaType));
    }
}
