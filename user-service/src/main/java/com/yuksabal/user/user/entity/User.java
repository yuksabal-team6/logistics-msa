package com.yuksabal.user.user.entity;

import com.yuksabal.common.auth.UserRole;
import com.yuksabal.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.SQLRestriction;

import java.util.UUID;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SQLRestriction("deleted_at IS NULL")
@Entity
@Table(name = "p_users")
public class User extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "user_id")
    private UUID id;

    // 소속 허브 → hub-service p_hubs.hub_id
    private UUID hubId;

    // 소속 업체 → company-service p_companies.company_id
    private UUID companyId;

    // UNIQUE는 부분 유니크 인덱스(삭제되지 않은 행만)로 관리하므로 unique = true를 쓰지 않는다 (결정 #15)
    @Column(nullable = false, length = 100)
    private String username;

    @Column(nullable = false, length = 100)
    private String slackId;

    @Column(nullable = false)
    private String email;

    // BCrypt 해시
    @Column(nullable = false)
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserRole role;

    @Column(nullable = false)
    private boolean isPublic;

    // 계정 활성 여부 (#67). 비활성이면 로그인·회원 API 사용 불가. 소프트 삭제(deleted_at)와 별개로, 다시 활성화할 수 있다
    // ddl-auto update로 컬럼이 추가될 때 기존 회원이 활성으로 채워지도록 DB 기본값을 둔다
    @ColumnDefault("true")
    @Column(nullable = false)
    private boolean isActive = true;

    @Builder
    public User(UUID hubId, UUID companyId, String username, String slackId, String email, String password,
                UserRole role, Boolean isPublic) {
        this.hubId = hubId;
        this.companyId = companyId;
        this.username = username;
        this.slackId = slackId;
        this.email = email;
        this.password = password;
        this.role = role != null ? role : UserRole.USER;
        this.isPublic = isPublic != null ? isPublic : true;
    }

    // 본인 프로필 수정. null인 값은 바꾸지 않는다 (중복·형식 검증은 서비스·DTO에서 끝낸 뒤 호출)
    public void updateProfile(String username, String email, String slackId, Boolean isPublic) {
        if (username != null) {
            this.username = username;
        }
        if (email != null) {
            this.email = email;
        }
        if (slackId != null) {
            this.slackId = slackId;
        }
        if (isPublic != null) {
            this.isPublic = isPublic;
        }
    }

    // MASTER의 소속 변경. null인 값은 바꾸지 않는다 (존재 확인은 hub·company 내부 API 연동 후 추가)
    public void changeAffiliation(UUID hubId, UUID companyId) {
        if (hubId != null) {
            this.hubId = hubId;
        }
        if (companyId != null) {
            this.companyId = companyId;
        }
    }

    // BCrypt 해시를 받는다 (원문 비밀번호를 넘기지 않는다)
    public void changePassword(String encodedPassword) {
        this.password = encodedPassword;
    }

    // MASTER만 호출 (권한·경계 조건은 서비스에서 확인)
    public void changeRole(UserRole role) {
        this.role = role;
    }

    public void changeActive(boolean active) {
        this.isActive = active;
    }

    public void delete(UUID deletedBy) {
        markDeleted(deletedBy);
    }
}
