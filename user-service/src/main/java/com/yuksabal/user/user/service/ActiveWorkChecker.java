package com.yuksabal.user.user.service;

import com.yuksabal.user.user.entity.User;

import java.util.List;

// 회원 삭제·비활성화·DELIVERY 역할 변경 전 "진행 중인 업무 관계" 확인 통로 (#68).
// 서비스마다 구현체를 하나씩 두고 Feign으로 각 서비스의 내부 API를 호출한다.
// 작업별로 필요한 Target(삭제는 전부, 비활성화·역할 변경은 DELIVERY)의 구현체가 없으면 막는다 (503, UserAdminService)
public interface ActiveWorkChecker {

    // 이 구현체가 확인하는 서비스
    Target target();

    // 대상 회원의 진행 중인 업무. 없으면 빈 목록. 확인하지 못하면(호출 실패·타임아웃·응답 이상) 예외를 던진다
    List<ActiveWork> findActiveWork(User user);

    // 삭제 전에 반드시 확인하는 서비스 (팀 답변: company 업체 관리자, order 진행 중 주문, delivery 진행 중 배송)
    enum Target {
        COMPANY, ORDER, DELIVERY
    }

    // type = 업무 종류 (예: "order"), reason = 삭제할 수 없는 이유 (오류 응답 details로 나간다)
    record ActiveWork(String type, String reason) {
    }
}
