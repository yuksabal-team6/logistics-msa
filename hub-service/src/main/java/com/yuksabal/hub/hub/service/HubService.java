package com.yuksabal.hub.hub.service;

import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.exception.CommonErrorCode;
import com.yuksabal.common.response.PageResponse;
import com.yuksabal.hub.hub.entity.Hub;
import com.yuksabal.hub.hub.repository.HubRepository;
import com.yuksabal.hub.hub.dto.request.HubSearchRequest;
import com.yuksabal.hub.hub.dto.response.HubResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HubService {

    private final HubRepository hubRepository;

    public PageResponse<HubResponse> getHubs(HubSearchRequest request, Pageable pageable) {
        Pageable stablePageable = withIdTiebreaker(pageable);
        Page<Hub> hubs = request.hasKeyword()
                ? hubRepository.searchByKeyword(request.keyword().trim(), stablePageable)
                : hubRepository.findAll(stablePageable);
        return PageResponse.from(hubs, HubResponse::from);
    }

    // 삭제된 허브는 @SQLRestriction으로 조회되지 않아 없는 허브와 같이 404로 응답한다
    public HubResponse getHub(UUID hubId) {
        return hubRepository.findById(hubId)
                .map(HubResponse::from)
                .orElseThrow(() -> new BusinessException(
                        CommonErrorCode.RESOURCE_NOT_FOUND,
                        "존재하지 않는 허브입니다.",
                        "hubId"
                ));
    }

    // 초기 허브는 created_at이 모두 같아서 정렬 값이 같으면 ID 순으로 고정해 페이지 간 중복·누락을 막는다.
    private Pageable withIdTiebreaker(Pageable pageable) {
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), pageable.getSort().and(Sort.by("id")));
    }
}
