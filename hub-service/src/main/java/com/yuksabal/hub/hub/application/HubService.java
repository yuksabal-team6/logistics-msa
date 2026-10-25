package com.yuksabal.hub.hub.application;

import com.yuksabal.hub.global.exception.HubExceptions;
import com.yuksabal.hub.hub.application.dto.HubResult;
import com.yuksabal.hub.hub.application.dto.HubSearchCondition;
import com.yuksabal.hub.hub.domain.HubRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static com.yuksabal.hub.hub.domain.HubSpecification.keywordContains;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HubService {

    private final HubRepository hubRepository;

    /**
     * 허브 목록을 조회한다. keyword가 있으면 검색, 없으면 전체 목록을 반환한다.
     */
    public Page<HubResult> getHubs(HubSearchCondition condition, Pageable pageable) {
        Pageable stablePageable = withIdTiebreaker(pageable);
        return hubRepository.findAll(keywordContains(condition.keyword()), stablePageable)
                .map(HubResult::from);
    }

    /**
     * 허브 단건 조회. 공개 상세 조회와 내부 API(company·user-service의 허브 존재 확인)가 함께 쓴다.
     * 응답 모양은 각 컨트롤러가 정한다 (공개: 전체 정보, 내부: 허브 ID만) (#284)
     * 삭제된 허브는 @SQLRestriction으로 조회되지 않아 없는 허브와 같이 404로 응답한다.
     */
    public HubResult getHub(UUID hubId) {
        return hubRepository.findById(hubId)
                .map(HubResult::from)
                .orElseThrow(HubExceptions::hubNotFound);
    }

    // 초기 허브는 created_at이 모두 같아서 정렬 값이 같으면 ID 순으로 고정해 페이지 간 중복·누락을 막는다.
    private Pageable withIdTiebreaker(Pageable pageable) {
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), pageable.getSort().and(Sort.by("id")));
    }
}
