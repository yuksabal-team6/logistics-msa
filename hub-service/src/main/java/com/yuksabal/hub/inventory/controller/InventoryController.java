package com.yuksabal.hub.inventory.controller;

import com.yuksabal.common.auth.UserRole;
import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.common.response.PageResponse;
import com.yuksabal.hub.global.swagger.ApiErrorCodes;
import com.yuksabal.hub.inventory.dto.request.InventorySearchRequest;
import com.yuksabal.hub.inventory.dto.response.InventoryResponse;
import com.yuksabal.hub.inventory.service.InventoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static com.yuksabal.common.exception.CommonErrorCode.*;

// 인증은 게이트웨이가 JWT로 처리하고, 역할은 게이트웨이가 넣어 준 X-User-Role로 서비스에서 확인한다.
@Tag(name = "Inventory", description = "허브 재고 조회 외부 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/inventories")
public class InventoryController {

    private static final String USER_ROLE_DESCRIPTION = "로그인한 사용자 역할입니다. (MASTER, USER, COMPANY, DELIVERY) 게이트웨이를 거치면 자동으로 들어갑니다.";

    private final InventoryService inventoryService;

    // ?hubId=&productId=&page=0&size=20&sort=quantity,desc (조건은 모두 선택)
    @Operation(summary = "재고 조건부 목록 조회 (MASTER)",
            description = "허브 ID·상품 ID로 조회한다. 없는 ID면 빈 목록. 정렬 가능: createdAt, quantity")
    @ApiErrorCodes(common = {VALIDATION_ERROR, UNAUTHORIZED, FORBIDDEN})
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<InventoryResponse>>> getInventories(
            @ParameterObject InventorySearchRequest request,
            @ParameterObject Pageable pageable,
            @Parameter(description = USER_ROLE_DESCRIPTION, example = "MASTER")
            @RequestHeader("X-User-Role") String role) {
        return ResponseEntity.ok(ApiResponse.success(
                inventoryService.getInventories(request, pageable, UserRole.from(role)),
                "목록을 조회했습니다."));
    }

    @Operation(summary = "재고 상세 조회 (MASTER 또는 허용된 사용자)",
            description = "재고 ID로 한 건을 조회한다. 권한이 없으면 403, 재고가 없으면 404. 허용된 사용자 기준이 정해지기 전까지 MASTER만 가능")
    @ApiErrorCodes(common = {VALIDATION_ERROR, UNAUTHORIZED, FORBIDDEN, RESOURCE_NOT_FOUND})
    @GetMapping("/{inventoryId}")
    public ResponseEntity<ApiResponse<InventoryResponse>> getInventory(
            @PathVariable UUID inventoryId,
            @Parameter(description = USER_ROLE_DESCRIPTION, example = "MASTER")
            @RequestHeader("X-User-Role") String role) {
        return ResponseEntity.ok(ApiResponse.success(
                inventoryService.getInventory(inventoryId, UserRole.from(role)),
                "재고를 상세 조회했습니다."));
    }

    // ?page=0&size=20&sort=quantity,desc
    @Operation(summary = "허브별 재고 조회 (MASTER 또는 허용된 사용자)",
            description = "허브의 재고 목록을 조회한다. 권한이 없으면 403, 허브가 없으면 404, 재고가 없으면 빈 목록. 정렬 가능: createdAt, quantity. 허용된 사용자 기준이 정해지기 전까지 MASTER만 가능")
    @ApiErrorCodes(common = {VALIDATION_ERROR, UNAUTHORIZED, FORBIDDEN, RESOURCE_NOT_FOUND})
    @GetMapping("/hubs/{hubId}")
    public ResponseEntity<ApiResponse<PageResponse<InventoryResponse>>> getInventoriesByHub(
            @PathVariable UUID hubId,
            @ParameterObject Pageable pageable,
            @Parameter(description = USER_ROLE_DESCRIPTION, example = "MASTER")
            @RequestHeader("X-User-Role") String role) {
        return ResponseEntity.ok(ApiResponse.success(
                inventoryService.getInventoriesByHub(hubId, pageable, UserRole.from(role)),
                "허브별 재고를 조회했습니다."));
    }
}
