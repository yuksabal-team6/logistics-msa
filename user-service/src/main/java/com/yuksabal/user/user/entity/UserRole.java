package com.yuksabal.user.user.entity;

// role_type. MASTER = 관리자, DELIVERY = 배송 담당자, COMPANY = 업체 관리자
// 회원가입은 항상 USER, 나머지 역할은 MASTER만 부여한다. api-gateway UserRole과 같은 값을 유지한다.
public enum UserRole {
    MASTER, USER, DELIVERY, COMPANY
}
