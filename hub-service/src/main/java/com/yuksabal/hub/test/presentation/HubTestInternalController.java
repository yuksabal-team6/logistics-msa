package com.yuksabal.hub.test.presentation;

import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.exception.CommonErrorCode;
import com.yuksabal.hub.hub.domain.Hub;
import com.yuksabal.hub.hub.domain.HubRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// [세팅 검증용] 서비스 간 내부 API. 게이트웨이에 라우팅이 없어 외부에서는 호출 불가 
@Tag(name = "Test (Internal)", description = "프로젝트 세팅 검증용 내부 API (확인 후 삭제)")
@RestController
@RequestMapping("/internal/test/hubs")
@RequiredArgsConstructor
public class HubTestInternalController {

    private final HubRepository hubRepository;

    @Operation(summary = "허브 조회 (내부)", description = "company-service가 Feign으로 호출")
    @GetMapping("/{hubId}")
    public HubTestResponse getHub(@PathVariable UUID hubId) {
        Hub hub = hubRepository.findById(hubId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
        return new HubTestResponse(hub.getId(), hub.getHubName());
    }

    public record HubTestResponse(UUID hubId, String hubName) {
    }
}
