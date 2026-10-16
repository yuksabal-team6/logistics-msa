package com.yuksabal.user.user.service;

import com.yuksabal.common.auth.UserRole;
import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.exception.CommonErrorCode;
import com.yuksabal.user.user.dto.request.ChangeRoleRequest;
import com.yuksabal.user.user.dto.request.ChangeStatusRequest;
import com.yuksabal.user.user.entity.User;
import com.yuksabal.user.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

// 마지막 활성 MASTER 보호. API로는 자기 자신 변경이 먼저 막혀 이 경로에 거의 닿지 않으므로 단위로 확인한다
// (예: 다른 MASTER가 이미 비활성인 상태에서 요청자 확인과 대상 확인 사이에 상태가 바뀐 경우)
class UserAdminServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final UserAdminService service =
            new UserAdminService(userRepository, new RequesterValidator(userRepository), mock(AffiliationValidator.class));

    private final User requester = master("master01");
    private final User target = master("master02");

    @BeforeEach
    void setUp() {
        given(userRepository.findById(requester.getId())).willReturn(Optional.of(requester));
        given(userRepository.findById(target.getId())).willReturn(Optional.of(target));
    }

    @Test
    void 활성_MASTER가_한_명뿐이면_강등은_422() {
        given(userRepository.countByRoleAndIsActiveTrue(UserRole.MASTER)).willReturn(1L);

        assertThatThrownBy(() -> service.changeRole(requester.getId(), UserRole.MASTER, target.getId(),
                new ChangeRoleRequest("USER")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(CommonErrorCode.BUSINESS_RULE_VIOLATION);
        assertThat(target.getRole()).isEqualTo(UserRole.MASTER);
    }

    @Test
    void 활성_MASTER가_한_명뿐이면_비활성화는_422() {
        given(userRepository.countByRoleAndIsActiveTrue(UserRole.MASTER)).willReturn(1L);

        assertThatThrownBy(() -> service.changeStatus(requester.getId(), UserRole.MASTER, target.getId(),
                new ChangeStatusRequest(false)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(CommonErrorCode.BUSINESS_RULE_VIOLATION);
        assertThat(target.isActive()).isTrue();
    }

    @Test
    void 활성_MASTER가_둘_이상이면_강등과_비활성화가_된다() {
        given(userRepository.countByRoleAndIsActiveTrue(UserRole.MASTER)).willReturn(2L);

        service.changeStatus(requester.getId(), UserRole.MASTER, target.getId(), new ChangeStatusRequest(false));
        assertThat(target.isActive()).isFalse();

        service.changeRole(requester.getId(), UserRole.MASTER, target.getId(), new ChangeRoleRequest("USER"));
        assertThat(target.getRole()).isEqualTo(UserRole.USER);
    }

    private static User master(String username) {
        User user = User.builder().username(username).password("$2a$hash").email(username + "@example.com")
                .slackId("U1").role(UserRole.MASTER).build();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }
}
