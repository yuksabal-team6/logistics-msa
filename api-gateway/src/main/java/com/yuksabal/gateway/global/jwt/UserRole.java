package com.yuksabal.gateway.global.jwt;

// JWT role 클레임에 허용되는 값 (common UserRole과 동일, gateway는 WebFlux라 common을 쓰지 않는다). MASTER = 관리자, COMPANY = 업체 관리자, DELIVERY = 배송 담당자
public enum UserRole {
    MASTER, USER, COMPANY, DELIVERY
}
