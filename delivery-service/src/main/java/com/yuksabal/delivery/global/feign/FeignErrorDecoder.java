package com.yuksabal.delivery.global.feign;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.Response;
import feign.Util;
import feign.codec.ErrorDecoder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

// Feign 응답이 2xx가 아니면 ApiResponse의 code·message를 읽어 예외로 바꾼다.
// 4xx → FeignClientException (업무 요청 실패), 5xx → FeignServerException (재시도·보상 대상)
// 타임아웃·연결 실패는 이 디코더를 거치지 않고 feign.RetryableException으로 던져진다.
@Component
@RequiredArgsConstructor
public class FeignErrorDecoder implements ErrorDecoder {

    private final ObjectMapper objectMapper;

    @Override
    public Exception decode(String methodKey, Response response) {
        int status = response.status();
        String code = "UNKNOWN_ERROR";
        String message = response.reason() != null ? response.reason() : "다른 서비스 호출에 실패했습니다.";

        // 상대 서비스가 ApiResponse 형식이 아닌 응답(로드밸런서 503 등)을 줄 수 있어 실패해도 기본값 사용
        try {
            if (response.body() != null) {
                JsonNode body = objectMapper.readTree(Util.toString(response.body().asReader(StandardCharsets.UTF_8)));
                if (body.hasNonNull("code")) {
                    code = body.get("code").asText();
                }
                if (body.hasNonNull("message")) {
                    message = body.get("message").asText();
                }
            }
        } catch (IOException ignored) {
        }

        if (status >= 400 && status < 500) {
            return new FeignClientException(status, code, message);
        }
        return new FeignServerException(status, code, message, methodKey);
    }
}
