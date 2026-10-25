package com.yuksabal.hub.hub.presentation;

import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.common.response.PageResponse;
import com.yuksabal.hub.global.swagger.ApiErrorCodes;
import com.yuksabal.hub.hub.application.dto.HubSearchCondition;
import com.yuksabal.hub.hub.presentation.dto.request.HubSearchRequest;
import com.yuksabal.hub.hub.presentation.dto.response.HubResponse;
import com.yuksabal.hub.hub.application.HubService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static com.yuksabal.common.exception.CommonErrorCode.RESOURCE_NOT_FOUND;
import static com.yuksabal.common.exception.CommonErrorCode.UNAUTHORIZED;
import static com.yuksabal.common.exception.CommonErrorCode.VALIDATION_ERROR;

// 인증은 게이트웨이가 JWT로 처리한다. 로그인한 모든 역할이 조회할 수 있어 서비스에서 권한을 다시 확인하지 않는다.
@Tag(name = "Hub", description = "허브 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/hubs")
public class HubController {

    private final HubService hubService;

    /**
     * 허브 목록을 조회·검색한다.
     * ?keyword=서울&page=0&size=20&sort=createdAt,desc (keyword 생략 시 전체 목록)
     */
    @Operation(summary = "허브 목록 조회 및 검색")
    @ApiErrorCodes(common = {VALIDATION_ERROR, UNAUTHORIZED})
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<HubResponse>>> getHubs(
            @Valid @ParameterObject HubSearchRequest request,
            @ParameterObject Pageable pageable) {
        HubSearchCondition condition = new HubSearchCondition(request.keyword());
        PageResponse<HubResponse> response = PageResponse.from(hubService.getHubs(condition, pageable), HubResponse::from);
        return ResponseEntity.ok(ApiResponse.success(response, "목록을 조회했습니다."));
    }

    /**
     * 허브 단건을 상세 조회한다.
     */
    @Operation(summary = "허브 상세 조회")
    @ApiErrorCodes(common = {VALIDATION_ERROR, UNAUTHORIZED, RESOURCE_NOT_FOUND})
    @GetMapping("/{hubId}")
    public ResponseEntity<ApiResponse<HubResponse>> getHub(@PathVariable UUID hubId) {
        HubResponse response = HubResponse.from(hubService.getHub(hubId));
        return ResponseEntity.ok(ApiResponse.success(response, "허브를 상세 조회했습니다."));
    }
}
