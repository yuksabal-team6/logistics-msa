package com.yuksabal.user.user.entity;

import com.yuksabal.common.auth.UserRole;
import com.yuksabal.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
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

    // BCrypt 해시를 받는다 (원문 비밀번호를 넘기지 않는다)
    public void changePassword(String encodedPassword) {
        this.password = encodedPassword;
    }

    public void delete(UUID deletedBy) {
        markDeleted(deletedBy);
    }
}
