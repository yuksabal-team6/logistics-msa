package com.yuksabal.order.sample.controller;

import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.order.global.swagger.ApiErrorCodes;
import com.yuksabal.order.sample.dto.request.SampleCreateRequest;
import com.yuksabal.order.sample.dto.response.SampleResponse;
import com.yuksabal.order.sample.service.SampleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

import static com.yuksabal.common.exception.CommonErrorCode.INVALID_INPUT;
import static com.yuksabal.order.global.exception.OrderErrorCode.SAMPLE_NOT_FOUND;

// 참고용 API: 응답 형식, 검증, 도메인 에러, Swagger 작성법 예시. 실제 기능 개발 후 sample 패키지째 삭제한다.
@Tag(name = "Sample", description = "참고용 샘플 API (삭제 예정)")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/orders/samples")
public class SampleController {

    private final SampleService sampleService;

    // 생성: 201 + ApiResponse.success(data, message)
    @Operation(summary = "샘플 생성")
    @ApiErrorCodes(common = {INVALID_INPUT})
    @PostMapping
    public ResponseEntity<ApiResponse<SampleResponse>> create(@Valid @RequestBody SampleCreateRequest request) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success(sampleService.create(request), "샘플이 생성되었습니다."));
    }

    // 조회: 200 + ApiResponse.success(data), 없으면 404 SAMPLE_NOT_FOUND
    @Operation(summary = "샘플 단건 조회")
    @ApiErrorCodes(common = {INVALID_INPUT}, value = {SAMPLE_NOT_FOUND})
    @GetMapping("/{sampleId}")
    public ResponseEntity<ApiResponse<SampleResponse>> get(@PathVariable UUID sampleId) {
        return ResponseEntity.ok(ApiResponse.success(sampleService.get(sampleId)));
    }
}
