package com.yuksabal.company.test.presentation;

import com.yuksabal.company.global.swagger.ApiErrorCodes;
import com.yuksabal.company.test.application.TestCompanyService;
import com.yuksabal.company.test.presentation.dto.TestCompanyCreateRequest;
import com.yuksabal.company.test.presentation.dto.TestCompanyResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

import static com.yuksabal.common.exception.CommonErrorCode.INVALID_INPUT;
import static com.yuksabal.common.exception.CommonErrorCode.NOT_FOUND;

// [세팅 검증용]
@Tag(name = "Test", description = "프로젝트 세팅 검증용 API (확인 후 삭제)")
@RestController
@RequestMapping("/api/v1/companies/test")
@RequiredArgsConstructor
public class TestCompanyController {

    private static final String USER_ID_HEADER = "X-User-Id";

    private final TestCompanyService testCompanyService;

    @Operation(summary = "테스트 업체 생성",
            description = "Feign으로 hub-service 내부 API를 호출해 허브 존재를 확인한 뒤 저장한다. X-User-Id를 넣으면 created_by에 기록된다.")
    @ApiErrorCodes(common = {INVALID_INPUT, NOT_FOUND})
    @PostMapping
    public ResponseEntity<TestCompanyResponse> create(
            @Parameter(description = "요청자 회원 ID (Audit 확인용)", example = "22222222-2222-2222-2222-222222222222")
            @RequestHeader(value = USER_ID_HEADER, required = false) UUID userId,
            @Valid @RequestBody TestCompanyCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(testCompanyService.create(request));
    }

    @Operation(summary = "테스트 업체 조회", description = "삭제된 업체는 404")
    @ApiErrorCodes(common = {NOT_FOUND})
    @GetMapping("/{companyId}")
    public ResponseEntity<TestCompanyResponse> get(@PathVariable UUID companyId) {
        return ResponseEntity.ok(testCompanyService.get(companyId));
    }

    @Operation(summary = "테스트 업체 삭제 (소프트 삭제)", description = "deleted_at, deleted_by(X-User-Id)를 채운다")
    @ApiErrorCodes(common = {NOT_FOUND})
    @DeleteMapping("/{companyId}")
    public ResponseEntity<TestCompanyResponse> delete(
            @Parameter(description = "삭제자 회원 ID", example = "22222222-2222-2222-2222-222222222222")
            @RequestHeader(value = USER_ID_HEADER, required = false) UUID userId,
            @PathVariable UUID companyId) {
        return ResponseEntity.ok(testCompanyService.delete(companyId, userId));
    }
}
