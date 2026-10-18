package com.yuksabal.order.sample.controller;

import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.common.response.PageResponse;
import com.yuksabal.order.global.swagger.ApiErrorCodes;
import com.yuksabal.order.sample.dto.request.SampleCreateRequest;
import com.yuksabal.order.sample.dto.response.SampleResponse;
import com.yuksabal.order.sample.service.SampleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

import static com.yuksabal.common.exception.CommonErrorCode.RESOURCE_NOT_FOUND;
import static com.yuksabal.common.exception.CommonErrorCode.VALIDATION_ERROR;

// 참고용 API: 응답 형식, 검증, 에러, 목록 조회, Swagger 작성법 예시. 실제 기능 개발 후 sample 패키지째 삭제한다.
// TODO 주문 기능 완료 후 sample 패키지 전체와 OrderErrorCode.SAMPLE_NOT_FOUND 삭제
@Tag(name = "Sample", description = "참고용 샘플 API (삭제 예정)")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/orders/samples")
public class SampleController {

    private final SampleService sampleService;

    // 생성: 201 + ApiResponse.success(data, message)
    @Operation(summary = "샘플 생성")
    @ApiErrorCodes(common = {VALIDATION_ERROR})
    @PostMapping
    public ResponseEntity<ApiResponse<SampleResponse>> create(@Valid @RequestBody SampleCreateRequest request) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success(sampleService.create(request), "샘플이 생성되었습니다."));
    }

    // 단건 조회: 200 + ApiResponse.success(data), 없으면 404 RESOURCE_NOT_FOUND
    @Operation(summary = "샘플 단건 조회")
    @ApiErrorCodes(common = {VALIDATION_ERROR, RESOURCE_NOT_FOUND})
    @GetMapping("/{sampleId}")
    public ResponseEntity<ApiResponse<SampleResponse>> get(@PathVariable UUID sampleId) {
        return ResponseEntity.ok(ApiResponse.success(sampleService.get(sampleId)));
    }

    // 목록 조회: ?page=0&size=20&sort=createdAt,desc (생략 시 기본값 page=0, size=20, createdAt DESC / size 최대 100)
    // @ParameterObject: Swagger에 page·size·sort를 각각 입력칸으로 보여준다
    @Operation(summary = "샘플 목록 조회")
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<SampleResponse>>> getList(@ParameterObject Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(PageResponse.from(sampleService.getList(pageable)), "목록을 조회했습니다."));
    }
}
