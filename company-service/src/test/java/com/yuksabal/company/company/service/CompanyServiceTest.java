package com.yuksabal.company.company.service;

import com.yuksabal.common.auth.UserRole;
import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.feign.FeignClientException;
import com.yuksabal.common.response.ApiResponse;
import com.yuksabal.company.company.dto.request.CompanyCreateRequest;
import com.yuksabal.company.company.dto.request.CompanyStatusRequest;
import com.yuksabal.company.company.dto.request.CompanyUpdateRequest;
import com.yuksabal.company.company.entity.Company;
import com.yuksabal.company.company.repository.CompanyRepository;
import com.yuksabal.company.global.client.HubClient;
import com.yuksabal.company.global.client.UserClient;
import com.yuksabal.company.global.client.dto.response.UserResponse;
import com.yuksabal.company.global.deletion.DeletionGuard;
import com.yuksabal.company.global.deletion.DeletionTarget;
import com.yuksabal.company.product.service.ProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static com.yuksabal.common.exception.CommonErrorCode.BUSINESS_RULE_VIOLATION;
import static com.yuksabal.common.exception.CommonErrorCode.DUPLICATE_RESOURCE;
import static com.yuksabal.common.exception.CommonErrorCode.FORBIDDEN;
import static com.yuksabal.common.exception.CommonErrorCode.RESOURCE_NOT_FOUND;
import static com.yuksabal.company.global.exception.CompanyErrorCode.COMPANY_ACCESS_DENIED;
import static com.yuksabal.company.global.exception.CompanyErrorCode.HAS_ACTIVE_WORK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

// Repository·Feign·다른 서비스는 가짜로 두고, 검증 순서와 분기만 확인한다
// DB 제약(부분 유니크 인덱스)과 잠금(#147)은 여기서 확인하지 않는다
@ExtendWith(MockitoExtension.class)
class CompanyServiceTest {

    private static final UUID MASTER_ID = UUID.randomUUID();
    private static final UUID OWNER_ID = UUID.randomUUID();
    private static final UUID OTHER_ID = UUID.randomUUID();
    private static final UUID HUB_ID = UUID.randomUUID();
    private static final UUID COMPANY_ID = UUID.randomUUID();

    @Mock
    private CompanyRepository companyRepository;
    @Mock
    private ProductService productService;
    @Mock
    private UserClient userClient;
    @Mock
    private HubClient hubClient;
    @Mock
    private DeletionGuard deletionGuard;
    @Mock
    private TransactionTemplate transactionTemplate;

    @InjectMocks
    private CompanyService companyService;

    @Nested
    class 업체_생성 {

        @Test
        void COMPANY가_다른_회원을_관리자로_지정하면_403이고_이후_검사를_하지_않는다() {
            assertThatThrownBy(() -> companyService.createCompany(createRequest(OTHER_ID, null), OWNER_ID, UserRole.COMPANY))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(COMPANY_ACCESS_DENIED));

            verifyNoInteractions(companyRepository, hubClient, userClient);
        }

        @Test
        void 사업자_등록번호가_중복이면_409이고_외부_서비스를_부르지_않는다() {
            given(companyRepository.existsByBusinessNumber("123-45-67890")).willReturn(true);

            assertThatThrownBy(() -> companyService.createCompany(createRequest(OWNER_ID, "123-45-67890"), MASTER_ID, UserRole.MASTER))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo(DUPLICATE_RESOURCE);
                        assertThat(e.getDetails()).extracting("field").containsExactly("businessNumber");
                    });

            verifyNoInteractions(hubClient, userClient);
        }

        @Test
        void 사업자_등록번호를_보내지_않으면_중복_검사를_하지_않는다() {
            givenValidOwner();
            given(companyRepository.save(any(Company.class))).willAnswer(inv -> inv.getArgument(0));

            companyService.createCompany(createRequest(OWNER_ID, null), MASTER_ID, UserRole.MASTER);

            verify(companyRepository, never()).existsByBusinessNumber(any());
        }

        @Test
        void 이미_다른_업체를_관리하는_회원이면_409() {
            given(companyRepository.existsByOwnerId(OWNER_ID)).willReturn(true);

            assertThatThrownBy(() -> companyService.createCompany(createRequest(OWNER_ID, null), MASTER_ID, UserRole.MASTER))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo(DUPLICATE_RESOURCE);
                        assertThat(e.getDetails()).extracting("field").containsExactly("ownerId");
                    });

            verifyNoInteractions(hubClient, userClient);
        }

        @Test
        void 허브가_없으면_hub의_404를_그대로_전달하고_user를_부르지_않는다() {
            FeignClientException notFound = new FeignClientException(404, "RESOURCE_NOT_FOUND", "존재하지 않는 허브입니다.", List.of());
            willThrow(notFound).given(hubClient).getHub(HUB_ID);

            assertThatThrownBy(() -> companyService.createCompany(createRequest(OWNER_ID, null), MASTER_ID, UserRole.MASTER))
                    .isSameAs(notFound);

            verifyNoInteractions(userClient);
        }

        @Test
        void 관리자_회원이_없으면_field를_ownerId로_바꿔_404() {
            given(userClient.getUser(OWNER_ID))
                    .willThrow(new FeignClientException(404, "RESOURCE_NOT_FOUND", "존재하지 않는 회원입니다.", List.of()));

            assertThatThrownBy(() -> companyService.createCompany(createRequest(OWNER_ID, null), MASTER_ID, UserRole.MASTER))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo(RESOURCE_NOT_FOUND);
                        assertThat(e.getDetails()).extracting("field").containsExactly("ownerId");
                    });
        }

        @Test
        void 비활성_회원은_관리자로_지정할_수_없다() {
            givenUser(OWNER_ID, false, "COMPANY", null);

            assertOwnerRuleViolation(() -> companyService.createCompany(createRequest(OWNER_ID, null), MASTER_ID, UserRole.MASTER),
                    "비활성화된 회원은 업체 관리자로 지정할 수 없습니다.");
        }

        @Test
        void COMPANY_역할이_아닌_회원은_관리자로_지정할_수_없다() {
            givenUser(OWNER_ID, true, "USER", null);

            assertOwnerRuleViolation(() -> companyService.createCompany(createRequest(OWNER_ID, null), MASTER_ID, UserRole.MASTER),
                    "COMPANY 역할 회원만 업체 관리자로 지정할 수 있습니다.");
        }

        @Test
        void 생성은_업체가_아직_없어_관리자의_소속을_확인하지_않는다() {
            givenUser(OWNER_ID, true, "COMPANY", null);   // 소속 없음
            given(companyRepository.save(any(Company.class))).willAnswer(inv -> inv.getArgument(0));

            var response = companyService.createCompany(createRequest(OWNER_ID, null), OWNER_ID, UserRole.COMPANY);

            assertThat(response.ownerId()).isEqualTo(OWNER_ID);
            assertThat(response.hubId()).isEqualTo(HUB_ID);
        }
    }

    @Nested
    class 업체_수정 {

        private Company company;

        @BeforeEach
        void setUp() {
            company = company(true);
            given(companyRepository.findById(COMPANY_ID)).willReturn(Optional.of(company));
        }

        @Test
        void 다른_업체의_관리자는_수정할_수_없다() {
            assertThatThrownBy(() -> companyService.updateCompany(COMPANY_ID, updateRequest(null, null, null), OTHER_ID, UserRole.COMPANY))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(COMPANY_ACCESS_DENIED));
        }

        @Test
        void COMPANY가_허브를_바꾸면_403() {
            assertThatThrownBy(() -> companyService.updateCompany(COMPANY_ID, updateRequest(UUID.randomUUID(), null, null), OWNER_ID, UserRole.COMPANY))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(FORBIDDEN));

            verifyNoInteractions(hubClient);
        }

        @Test
        void COMPANY가_지금과_같은_허브를_보내면_변경이_아니라_허용한다() {
            companyService.updateCompany(COMPANY_ID, updateRequest(HUB_ID, null, null), OWNER_ID, UserRole.COMPANY);

            verifyNoInteractions(hubClient, deletionGuard);
        }

        @Test
        void 사업자_등록번호가_그대로면_중복_검사를_하지_않는다() {
            companyService.updateCompany(COMPANY_ID, updateRequest(null, null, "123-45-67890"), OWNER_ID, UserRole.COMPANY);

            verify(companyRepository, never()).existsByBusinessNumber(anyString());
        }

        @Test
        void 새_관리자가_이_업체_소속이_아니면_422() {
            givenUser(OTHER_ID, true, "COMPANY", UUID.randomUUID());

            assertOwnerRuleViolation(() -> companyService.updateCompany(COMPANY_ID, updateRequest(null, OTHER_ID, null), MASTER_ID, UserRole.MASTER),
                    "이 업체 소속 회원만 업체 관리자로 지정할 수 있습니다.");
            assertThat(company.getOwnerId()).isEqualTo(OWNER_ID);
        }

        @Test
        void MASTER는_허브와_관리자를_함께_바꿀_수_있다() {
            UUID newHubId = UUID.randomUUID();
            givenUser(OTHER_ID, true, "COMPANY", COMPANY_ID);

            companyService.updateCompany(COMPANY_ID, updateRequest(newHubId, OTHER_ID, null), MASTER_ID, UserRole.MASTER);

            verify(hubClient).getHub(newHubId);
            verify(deletionGuard).validateHubChangeable(COMPANY_ID);
            assertThat(company.getHubId()).isEqualTo(newHubId);
            assertThat(company.getOwnerId()).isEqualTo(OTHER_ID);
        }

        @Test
        void 재고나_진행_중_주문이_남아_있으면_허브를_바꾸지_않는다() {
            UUID newHubId = UUID.randomUUID();
            willThrow(new BusinessException(HAS_ACTIVE_WORK)).given(deletionGuard).validateHubChangeable(COMPANY_ID);

            assertThatThrownBy(() -> companyService.updateCompany(COMPANY_ID, updateRequest(newHubId, null, null), MASTER_ID, UserRole.MASTER))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(HAS_ACTIVE_WORK));

            assertThat(company.getHubId()).isEqualTo(HUB_ID);
        }

        @Test
        void 새_허브가_없으면_404이고_남은_업무를_확인하지_않는다() {
            UUID newHubId = UUID.randomUUID();
            FeignClientException notFound = new FeignClientException(404, "RESOURCE_NOT_FOUND", "존재하지 않는 허브입니다.", List.of());
            willThrow(notFound).given(hubClient).getHub(newHubId);

            assertThatThrownBy(() -> companyService.updateCompany(COMPANY_ID, updateRequest(newHubId, null, null), MASTER_ID, UserRole.MASTER))
                    .isSameAs(notFound);

            verifyNoInteractions(deletionGuard);
        }

        @Test
        void 허브를_바꾸지_않으면_남은_업무를_확인하지_않는다() {
            companyService.updateCompany(COMPANY_ID, updateRequest(null, null, "123-45-67890"), MASTER_ID, UserRole.MASTER);

            verifyNoInteractions(deletionGuard);
        }
    }

    @Nested
    class 업체_상태_변경 {

        @Test
        void MASTER가_아니면_403이고_업체를_조회하지_않는다() {
            assertThatThrownBy(() -> companyService.changeCompanyStatus(COMPANY_ID, new CompanyStatusRequest(false), UserRole.COMPANY))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(FORBIDDEN));

            verifyNoInteractions(companyRepository);
        }

        @Test
        void 업체를_잠그고_조회한다() {
            given(companyRepository.findByIdForUpdate(COMPANY_ID)).willReturn(Optional.of(company(true)));

            companyService.changeCompanyStatus(COMPANY_ID, new CompanyStatusRequest(false), UserRole.MASTER);

            verify(companyRepository, never()).findById(any());
        }

        @Test
        void 비활성화하면_소속_상품도_비활성화한다() {
            Company company = company(true);
            given(companyRepository.findByIdForUpdate(COMPANY_ID)).willReturn(Optional.of(company));

            var response = companyService.changeCompanyStatus(COMPANY_ID, new CompanyStatusRequest(false), UserRole.MASTER);

            verify(productService).deactivateAllByCompany(COMPANY_ID);
            assertThat(response.active()).isFalse();
        }

        @Test
        void 다시_활성화해도_상품은_되돌리지_않는다() {
            Company company = company(false);
            given(companyRepository.findByIdForUpdate(COMPANY_ID)).willReturn(Optional.of(company));

            var response = companyService.changeCompanyStatus(COMPANY_ID, new CompanyStatusRequest(true), UserRole.MASTER);

            verifyNoInteractions(productService);
            assertThat(response.active()).isTrue();
        }

        @Test
        void 이미_같은_상태면_바꾸지_않는다() {
            given(companyRepository.findByIdForUpdate(COMPANY_ID)).willReturn(Optional.of(company(false)));

            companyService.changeCompanyStatus(COMPANY_ID, new CompanyStatusRequest(false), UserRole.MASTER);

            verifyNoInteractions(productService);
            verify(companyRepository, never()).flush();
        }
    }

    @Nested
    class 업체_삭제 {

        @BeforeEach
        void setUp() {
            // 트랜잭션 없이 콜백만 바로 실행한다. 권한(403)에서 끝나는 테스트는 부르지 않아 lenient
            lenient().doAnswer(inv -> {
                Consumer<TransactionStatus> action = inv.getArgument(0);
                action.accept(null);
                return null;
            }).when(transactionTemplate).executeWithoutResult(any());
        }

        @Test
        void MASTER가_아니면_403이고_아무것도_확인하지_않는다() {
            assertThatThrownBy(() -> companyService.deleteCompany(COMPANY_ID, OWNER_ID, UserRole.COMPANY))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(FORBIDDEN));

            verifyNoInteractions(companyRepository, deletionGuard, transactionTemplate);
        }

        @Test
        void 활성_업체는_422이고_남은_업무를_확인하지_않는다() {
            given(companyRepository.findById(COMPANY_ID)).willReturn(Optional.of(company(true)));

            assertThatThrownBy(() -> companyService.deleteCompany(COMPANY_ID, MASTER_ID, UserRole.MASTER))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(BUSINESS_RULE_VIOLATION));

            verifyNoInteractions(deletionGuard);
        }

        @Test
        void 남은_업무가_있으면_삭제하지_않는다() {
            Company company = company(false);
            given(companyRepository.findById(COMPANY_ID)).willReturn(Optional.of(company));
            willThrow(new BusinessException(HAS_ACTIVE_WORK))
                    .given(deletionGuard).validateDeletable(DeletionTarget.COMPANY, COMPANY_ID);

            assertThatThrownBy(() -> companyService.deleteCompany(COMPANY_ID, MASTER_ID, UserRole.MASTER))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(HAS_ACTIVE_WORK));

            verifyNoInteractions(productService);
            assertThat(company.getDeletedAt()).isNull();
        }

        @Test
        void 확인하는_동안_다시_활성화됐으면_422이고_삭제하지_않는다() {
            Company company = company(false);
            given(companyRepository.findById(COMPANY_ID)).willReturn(Optional.of(company));
            // 남은 업무 확인 중에 다른 요청이 업체를 다시 활성화한 상황
            willAnswer(inv -> {
                company.changeActive(true);
                return null;
            }).given(deletionGuard).validateDeletable(DeletionTarget.COMPANY, COMPANY_ID);

            assertThatThrownBy(() -> companyService.deleteCompany(COMPANY_ID, MASTER_ID, UserRole.MASTER))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(BUSINESS_RULE_VIOLATION));

            verifyNoInteractions(productService);
            assertThat(company.getDeletedAt()).isNull();
        }

        @Test
        void 남은_업무가_없으면_업체와_소속_상품을_삭제한다() {
            Company company = company(false);
            given(companyRepository.findById(COMPANY_ID)).willReturn(Optional.of(company));

            companyService.deleteCompany(COMPANY_ID, MASTER_ID, UserRole.MASTER);

            verify(deletionGuard).validateDeletable(DeletionTarget.COMPANY, COMPANY_ID);
            verify(productService).deleteAllByCompany(COMPANY_ID, MASTER_ID);
            assertThat(company.getDeletedAt()).isNotNull();
            assertThat(company.getDeletedBy()).isEqualTo(MASTER_ID);
        }
    }

    private static CompanyCreateRequest createRequest(UUID ownerId, String businessNumber) {
        return new CompanyCreateRequest("부산 수산물 도매", HUB_ID, ownerId, businessNumber, "부산광역시 중구 중앙대로 1", null);
    }

    private static CompanyUpdateRequest updateRequest(UUID hubId, UUID ownerId, String businessNumber) {
        return new CompanyUpdateRequest(null, hubId, ownerId, businessNumber, null, null);
    }

    private static Company company(boolean active) {
        Company company = Company.builder()
                .hubId(HUB_ID)
                .ownerId(OWNER_ID)
                .companyName("부산 수산물 도매")
                .businessNumber("123-45-67890")
                .address("부산광역시 중구 중앙대로 1")
                .build();
        company.changeActive(active);
        return company;
    }

    private void givenValidOwner() {
        givenUser(OWNER_ID, true, "COMPANY", null);
    }

    private void givenUser(UUID userId, boolean active, String role, UUID companyId) {
        given(userClient.getUser(userId)).willReturn(ApiResponse.success(new UserResponse(active, role, companyId)));
    }

    private static void assertOwnerRuleViolation(Runnable call, String message) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(BUSINESS_RULE_VIOLATION);
                    assertThat(e.getMessage()).isEqualTo(message);
                    assertThat(e.getDetails()).extracting("field").containsExactly("ownerId");
                });
    }
}
