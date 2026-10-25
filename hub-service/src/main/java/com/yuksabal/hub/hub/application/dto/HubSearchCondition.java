package com.yuksabal.hub.hub.application.dto;

// 허브 목록 검색 조건 (서비스 입력). 요청 DTO와 분리해 API 파라미터가 바뀌어도 서비스는 그대로다 (#284)
// keyword가 없으면 전체 목록
public record HubSearchCondition(
        String keyword
) {
}
