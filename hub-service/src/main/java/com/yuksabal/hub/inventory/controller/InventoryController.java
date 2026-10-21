package com.yuksabal.hub.inventory.controller;

import com.yuksabal.common.auth.UserRole;
import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.common.response.PageResponse;
import com.yuksabal.hub.global.swagger.ApiErrorCodes;
import com.yuksabal.hub.inventory.dto.request.InventoryInboundRequest;
import com.yuksabal.hub.inventory.dto.request.InventoryLogSearchRequest;
import com.yuksabal.hub.inventory.dto.request.InventoryQuantityUpdateRequest;
import com.yuksabal.hub.inventory.dto.request.InventorySearchRequest;
import com.yuksabal.hub.inventory.dto.response.InventoryLogResponse;
import com.yuksabal.hub.inventory.dto.response.InventoryResponse;
import com.yuksabal.hub.inventory.service.InventoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static com.yuksabal.common.exception.CommonErrorCode.*;
import static com.yuksabal.hub.global.exception.HubErrorCode.INVENTORY_CONFLICT;
import static com.yuksabal.hub.global.exception.HubErrorCode.INVENTORY_CREATE_CONFLICT;
import static com.yuksabal.hub.global.exception.HubErrorCode.INVENTORY_QUANTITY_CONFLICT;

// 인증은 게이트웨이가 JWT로 처리하고, 역할은 게이트웨이가 넣어 준 X-User-Role로 서비스에서 확인한다.
@Tag(name = "Inventory", description = "허브 재고 외부 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/inventories")
public class InventoryController {

    private static final String USER_ID_DESCRIPTION = "로그인한 사용자 ID입니다. 게이트웨이를 거치면 자동으로 들어갑니다.";
    private static final String USER_ROLE_DESCRIPTION ="로그인한 사용자 역할입니다. (MASTER, USER, COMPANY, DELIVERY) 게이트웨이를 거치면 자동으로 들어갑니다.";

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

    // ?inventoryId=&hubId=&orderId=&changeType=&page=0&size=20&sort=createdAt,desc (조건은 모두 선택)
    // /{inventoryId}보다 고정 경로가 먼저 매칭된다
    @Operation(summary = "재고 변경 이력 조건부 조회 (MASTER)",
            description = "재고 ID·허브 ID·주문 ID·변경 유형으로 조회한다. 조건은 함께 쓰면 AND, 없는 ID면 빈 목록. "
                    + "기본 정렬은 최신순(createdAt,desc), 정렬 가능: createdAt")
    @ApiErrorCodes(common = {VALIDATION_ERROR, UNAUTHORIZED, FORBIDDEN})
    @GetMapping("/logs")
    public ResponseEntity<ApiResponse<PageResponse<InventoryLogResponse>>> getInventoryLogs(
            @ParameterObject InventoryLogSearchRequest request,
            @ParameterObject @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
            @Parameter(description = USER_ROLE_DESCRIPTION, example = "MASTER")
            @RequestHeader("X-User-Role") String role) {
        return ResponseEntity.ok(ApiResponse.success(
                inventoryService.getInventoryLogs(request, pageable, UserRole.from(role)),
                "재고 변경 이력을 조회했습니다."));
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

    @Operation(summary = "재고 입고 (MASTER)",
            description = "허브에 상품을 입고한다. 재고가 있으면 수량을 늘리고, 없으면 새로 만든다. 수량이 1 미만이면 400, 허브·상품이 없으면 404, "
                    + "비활성 상품(비활성 업체의 상품 포함)이거나 상품 업체의 관리 허브가 아니면 422, 같은 재고를 동시에 처음 입고하면 409 (다시 시도하면 수량이 늘어난다)")
    @ApiErrorCodes(common = {VALIDATION_ERROR, UNAUTHORIZED, FORBIDDEN, RESOURCE_NOT_FOUND, BUSINESS_RULE_VIOLATION},
            value = {INVENTORY_CREATE_CONFLICT})
    @PostMapping("/inbound")
    public ResponseEntity<ApiResponse<InventoryResponse>> inbound(
            @Valid @RequestBody InventoryInboundRequest request,
            @Parameter(description = USER_ID_DESCRIPTION, example = "10000000-0000-4000-8000-000000000001")
            @RequestHeader("X-User-Id") UUID userId,
            @Parameter(description = USER_ROLE_DESCRIPTION, example = "MASTER")
            @RequestHeader("X-User-Role") String role) {
        return ResponseEntity.ok(ApiResponse.success(
                inventoryService.inbound(request, userId, UserRole.from(role)),
                "재고를 입고했습니다."));
    }

    @Operation(summary = "재고 수량 조정 (MASTER)",
            description = "실사 결과로 재고 수량을 직접 바꾼다. 조회할 때 본 수량(expectedQuantity)이 현재 수량과 다르면 409 "
                    + "(그 사이 주문 차감·입고로 바뀐 경우, details에 현재 수량). 조정할 수량이 조회한 수량과 같으면 400 (details의 field는 quantityChanged), 재고가 없으면 404")
    @ApiErrorCodes(common = {VALIDATION_ERROR, UNAUTHORIZED, FORBIDDEN, RESOURCE_NOT_FOUND}, value = {INVENTORY_QUANTITY_CONFLICT})
    @PatchMapping("/{inventoryId}")
    public ResponseEntity<ApiResponse<InventoryResponse>> adjustQuantity(
            @PathVariable UUID inventoryId,
            @Valid @RequestBody InventoryQuantityUpdateRequest request,
            @Parameter(description = USER_ID_DESCRIPTION, example = "10000000-0000-4000-8000-000000000001")
            @RequestHeader("X-User-Id") UUID userId,
            @Parameter(description = USER_ROLE_DESCRIPTION, example = "MASTER")
            @RequestHeader("X-User-Role") String role) {
        return ResponseEntity.ok(ApiResponse.success(
                inventoryService.adjustQuantity(inventoryId, request, userId, UserRole.from(role)),
                "재고 수량을 조정했습니다."));
    }

    @Operation(summary = "상품별 재고 조회 (MASTER 또는 해당 상품의 업체)",
            description = "상품 재고는 업체 관리 허브 한 곳에만 있어 한 건을 조회한다. 재고가 없으면 404, 해당 상품의 업체 관리자가 아니면 403")
    @ApiErrorCodes(common = {VALIDATION_ERROR, UNAUTHORIZED, FORBIDDEN, RESOURCE_NOT_FOUND}, value = {INVENTORY_CONFLICT})
    @GetMapping("/products/{productId}")
    public ResponseEntity<ApiResponse<InventoryResponse>> getInventoryByProduct(
            @PathVariable UUID productId,
            @Parameter(description = USER_ID_DESCRIPTION, example = "10000000-0000-4000-8000-000000000001")
            @RequestHeader("X-User-Id") UUID userId,
            @Parameter(description = USER_ROLE_DESCRIPTION, example = "MASTER")
            @RequestHeader("X-User-Role") String role) {
        return ResponseEntity.ok(ApiResponse.success(
                inventoryService.getInventoryByProduct(productId, userId, UserRole.from(role)),
                "상품별 재고를 조회했습니다."));
    }
}
