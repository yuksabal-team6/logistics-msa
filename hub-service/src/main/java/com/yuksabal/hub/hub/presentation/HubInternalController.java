package com.yuksabal.hub.hub.presentation;

import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.hub.global.swagger.ApiErrorCodes;
import com.yuksabal.hub.hub.presentation.dto.response.HubInternalResponse;
import com.yuksabal.hub.hub.application.HubService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static com.yuksabal.common.exception.CommonErrorCode.RESOURCE_NOT_FOUND;
import static com.yuksabal.common.exception.CommonErrorCode.VALIDATION_ERROR;

// 게이트웨이에 라우팅하지 않는 Feign 전용 API (company-service 업체 생성, user-service 회원가입에서 호출)
@Tag(name = "Hub Internal", description = "허브 내부 API (Feign 전용)")
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/hubs")
public class HubInternalController {

    private final HubService hubService;

    // 호출 측은 404를 받으면 생성·가입을 실패 처리한다
    @Operation(summary = "허브 존재 확인", description = "허브가 있으면 허브 ID를 반환한다. 허브가 없으면 404.")
    @ApiErrorCodes(common = {VALIDATION_ERROR, RESOURCE_NOT_FOUND})
    @GetMapping("/{hubId}")
    public ResponseEntity<ApiResponse<HubInternalResponse>> getHub(
            @Parameter(description = "허브 ID", example = "00000000-0000-0000-0000-000000000001")
            @PathVariable UUID hubId) {
        return ResponseEntity.ok(ApiResponse.success(HubInternalResponse.from(hubService.getHub(hubId))));
    }
}
