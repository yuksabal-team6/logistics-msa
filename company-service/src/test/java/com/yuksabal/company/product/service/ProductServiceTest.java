package com.yuksabal.company.product.service;

import com.yuksabal.common.auth.UserRole;
import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.company.company.entity.Company;
import com.yuksabal.company.company.repository.CompanyRepository;
import com.yuksabal.company.global.activework.ActiveWorkGuard;
import com.yuksabal.company.global.activework.ActiveWorkTarget;
import com.yuksabal.company.product.dto.request.ProductCreateRequest;
import com.yuksabal.company.product.dto.request.ProductStatusRequest;
import com.yuksabal.company.product.dto.request.ProductUpdateRequest;
import com.yuksabal.company.product.entity.Product;
import com.yuksabal.company.product.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static com.yuksabal.common.exception.CommonErrorCode.BUSINESS_RULE_VIOLATION;
import static com.yuksabal.common.exception.CommonErrorCode.RESOURCE_NOT_FOUND;
import static com.yuksabal.company.global.exception.CompanyErrorCode.COMPANY_ACCESS_DENIED;
import static com.yuksabal.company.global.exception.CompanyErrorCode.HAS_ACTIVE_WORK;
import static com.yuksabal.company.support.BusinessErrorAssertions.assertBusinessError;
import static com.yuksabal.company.support.BusinessErrorAssertions.catchBusinessError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

// Repository·삭제 확인은 가짜로 두고, 검증 순서와 분기만 확인한다
// 실제 잠금이 동시 요청을 순서대로 처리하는지(#147)는 여기서 확인하지 않는다
@SuppressWarnings("NonAsciiCharacters")
@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    private static final UUID MASTER_ID = UUID.randomUUID();
    private static final UUID OWNER_ID = UUID.randomUUID();
    private static final UUID OTHER_ID = UUID.randomUUID();
    private static final UUID COMPANY_ID = UUID.randomUUID();
    private static final UUID PRODUCT_ID = UUID.randomUUID();

    @Mock
    private ProductRepository productRepository;
    @Mock
    private CompanyRepository companyRepository;
    @Mock
    private ActiveWorkGuard activeWorkGuard;
    @Mock
    private TransactionTemplate transactionTemplate;

    @InjectMocks
    private ProductService productService;

    @Nested
    class 상품_등록 {

        private final ProductCreateRequest request = new ProductCreateRequest("고등어", "냉동", 5000L);

        @Test
        void 업체를_잠그고_조회한다() {
            given(companyRepository.findByIdForUpdate(COMPANY_ID)).willReturn(Optional.of(company(true)));
            given(productRepository.save(any(Product.class))).willAnswer(inv -> inv.getArgument(0));

            var response = productService.createProduct(COMPANY_ID, request, OWNER_ID, UserRole.COMPANY);

            verify(companyRepository, never()).findById(any());
            assertThat(response.productName()).isEqualTo("고등어");
            assertThat(response.active()).isTrue();
        }

        @Test
        void 없거나_삭제된_업체면_404() {
            given(companyRepository.findByIdForUpdate(COMPANY_ID)).willReturn(Optional.empty());

            BusinessException e = catchBusinessError(() -> productService.createProduct(COMPANY_ID, request, MASTER_ID, UserRole.MASTER),
                    RESOURCE_NOT_FOUND);
            assertThat(e.getDetails()).extracting("field").containsExactly("companyId");
        }

        @Test
        void 다른_업체의_관리자는_등록할_수_없다() {
            given(companyRepository.findByIdForUpdate(COMPANY_ID)).willReturn(Optional.of(company(true)));

            assertBusinessError(() -> productService.createProduct(COMPANY_ID, request, OTHER_ID, UserRole.COMPANY),
                    COMPANY_ACCESS_DENIED);

            verify(productRepository, never()).save(any());
        }

        @Test
        void 비활성_업체에는_등록할_수_없다() {
            given(companyRepository.findByIdForUpdate(COMPANY_ID)).willReturn(Optional.of(company(false)));

            BusinessException e = catchBusinessError(() -> productService.createProduct(COMPANY_ID, request, MASTER_ID, UserRole.MASTER),
                    BUSINESS_RULE_VIOLATION);
            assertThat(e.getMessage()).isEqualTo("비활성 업체에는 상품을 등록할 수 없습니다.");

            verify(productRepository, never()).save(any());
        }
    }

    @Nested
    class 상품_수정 {

        @Test
        void 다른_업체의_관리자는_수정할_수_없다() {
            Product product = product(company(true), true);
            given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product));

            assertBusinessError(() -> productService.updateProduct(PRODUCT_ID, new ProductUpdateRequest("갈치", null, null), OTHER_ID, UserRole.COMPANY),
                    COMPANY_ACCESS_DENIED);

            assertThat(product.getProductName()).isEqualTo("고등어");
        }

        @Test
        void 보낸_값만_바꾸고_나머지는_유지한다() {
            Product product = product(company(true), true);
            given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product));

            var response = productService.updateProduct(PRODUCT_ID, new ProductUpdateRequest(null, null, 7000L), OWNER_ID, UserRole.COMPANY);

            assertThat(response.productName()).isEqualTo("고등어");
            assertThat(response.description()).isEqualTo("냉동");
            assertThat(response.unitPrice()).isEqualTo(7000L);
        }
    }

    @Nested
    class 상품_상태_변경 {

        @Test
        void 업체_ID_조회_업체_잠금_상품_조회_순서로_읽는다() {
            // #147: 상품을 잠금 전에 읽으면, 잠금을 기다리는 동안 업체 비활성화가 바꾼 상품 상태를 놓친다
            givenStatusTarget(company(true), product(company(true), false));

            productService.changeProductStatus(PRODUCT_ID, new ProductStatusRequest(true), OWNER_ID, UserRole.COMPANY);

            InOrder order = inOrder(productRepository, companyRepository);
            order.verify(productRepository).findCompanyIdById(PRODUCT_ID);
            order.verify(companyRepository).findByIdForUpdate(COMPANY_ID);
            order.verify(productRepository).findById(PRODUCT_ID);
        }

        @Test
        void 없거나_삭제된_상품이면_404이고_업체를_잠그지_않는다() {
            given(productRepository.findCompanyIdById(PRODUCT_ID)).willReturn(Optional.empty());

            BusinessException e = catchBusinessError(() -> productService.changeProductStatus(PRODUCT_ID, new ProductStatusRequest(false), MASTER_ID, UserRole.MASTER),
                    RESOURCE_NOT_FOUND);
            assertThat(e.getDetails()).extracting("field").containsExactly("productId");

            verifyNoInteractions(companyRepository);
        }

        @Test
        void 다른_업체의_관리자는_바꿀_수_없고_상품을_조회하지_않는다() {
            given(productRepository.findCompanyIdById(PRODUCT_ID)).willReturn(Optional.of(COMPANY_ID));
            given(companyRepository.findByIdForUpdate(COMPANY_ID)).willReturn(Optional.of(company(true)));

            assertBusinessError(() -> productService.changeProductStatus(PRODUCT_ID, new ProductStatusRequest(false), OTHER_ID, UserRole.COMPANY),
                    COMPANY_ACCESS_DENIED);

            verify(productRepository, never()).findById(any());
        }

        @Test
        void 비활성_업체의_상품은_활성화할_수_없다() {
            Company company = company(false);
            Product product = product(company, false);
            givenStatusTarget(company, product);

            BusinessException e = catchBusinessError(() -> productService.changeProductStatus(PRODUCT_ID, new ProductStatusRequest(true), MASTER_ID, UserRole.MASTER),
                    BUSINESS_RULE_VIOLATION);
            assertThat(e.getMessage()).isEqualTo("비활성 업체의 상품은 활성화할 수 없습니다.");

            assertThat(product.isActive()).isFalse();
        }

        @Test
        void 이미_같은_상태면_비활성_업체여도_바꾸지_않고_200() {
            Company company = company(false);
            givenStatusTarget(company, product(company, false));

            var response = productService.changeProductStatus(PRODUCT_ID, new ProductStatusRequest(false), MASTER_ID, UserRole.MASTER);

            assertThat(response.active()).isFalse();
            verify(productRepository, never()).flush();
        }

        @Test
        void 활성_업체의_상품은_비활성화와_활성화를_오갈_수_있다() {
            Company company = company(true);
            Product product = product(company, true);
            givenStatusTarget(company, product);

            productService.changeProductStatus(PRODUCT_ID, new ProductStatusRequest(false), OWNER_ID, UserRole.COMPANY);
            assertThat(product.isActive()).isFalse();

            productService.changeProductStatus(PRODUCT_ID, new ProductStatusRequest(true), OWNER_ID, UserRole.COMPANY);
            assertThat(product.isActive()).isTrue();
        }

        private void givenStatusTarget(Company company, Product product) {
            given(productRepository.findCompanyIdById(PRODUCT_ID)).willReturn(Optional.of(COMPANY_ID));
            given(companyRepository.findByIdForUpdate(COMPANY_ID)).willReturn(Optional.of(company));
            given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product));
        }
    }

    @Nested
    class 상품_삭제 {

        @BeforeEach
        void setUp() {
            // 트랜잭션 없이 콜백만 바로 실행한다
            lenient().doAnswer(inv -> {
                Consumer<TransactionStatus> action = inv.getArgument(0);
                action.accept(null);
                return null;
            }).when(transactionTemplate).executeWithoutResult(any());
        }

        @Test
        void 다른_업체의_관리자는_삭제할_수_없고_남은_업무를_확인하지_않는다() {
            given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product(company(true), false)));

            assertBusinessError(() -> productService.deleteProduct(PRODUCT_ID, OTHER_ID, UserRole.COMPANY),
                    COMPANY_ACCESS_DENIED);

            verifyNoInteractions(activeWorkGuard);
        }

        @Test
        void 활성_상품은_422이고_남은_업무를_확인하지_않는다() {
            given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product(company(true), true)));

            BusinessException e = catchBusinessError(() -> productService.deleteProduct(PRODUCT_ID, OWNER_ID, UserRole.COMPANY),
                    BUSINESS_RULE_VIOLATION);
            assertThat(e.getMessage()).isEqualTo("비활성화한 상품만 삭제할 수 있습니다.");

            verifyNoInteractions(activeWorkGuard);
        }

        @Test
        void 남은_업무가_있으면_삭제하지_않는다() {
            Product product = product(company(true), false);
            given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product));
            willThrow(new BusinessException(HAS_ACTIVE_WORK))
                    .given(activeWorkGuard).validateDeletable(ActiveWorkTarget.PRODUCT, PRODUCT_ID);

            assertBusinessError(() -> productService.deleteProduct(PRODUCT_ID, OWNER_ID, UserRole.COMPANY), HAS_ACTIVE_WORK);

            assertThat(product.getDeletedAt()).isNull();
        }

        @Test
        void 확인하는_동안_다시_활성화됐으면_422이고_삭제하지_않는다() {
            Product product = product(company(true), false);
            given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product));
            // 남은 업무 확인 중에 다른 요청이 상품을 다시 활성화한 상황
            willAnswer(inv -> {
                product.changeActive(true);
                return null;
            }).given(activeWorkGuard).validateDeletable(ActiveWorkTarget.PRODUCT, PRODUCT_ID);

            assertBusinessError(() -> productService.deleteProduct(PRODUCT_ID, OWNER_ID, UserRole.COMPANY),
                    BUSINESS_RULE_VIOLATION);

            assertThat(product.getDeletedAt()).isNull();
        }

        @Test
        void 남은_업무가_없으면_삭제한다() {
            Product product = product(company(true), false);
            given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product));

            productService.deleteProduct(PRODUCT_ID, OWNER_ID, UserRole.COMPANY);

            verify(activeWorkGuard).validateDeletable(ActiveWorkTarget.PRODUCT, PRODUCT_ID);
            assertThat(product.getDeletedAt()).isNotNull();
            assertThat(product.getDeletedBy()).isEqualTo(OWNER_ID);
        }
    }

    @Nested
    class 내부_상품_조회 {

        @Test
        void 상품이_활성이어도_업체가_비활성이면_active는_false() {
            // 연쇄 비활성화 전 데이터 등으로 상품만 활성인 경우에도 order·hub가 새 거래를 막을 수 있게 (#147)
            given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product(company(false), true)));

            assertThat(productService.getProductForInternal(PRODUCT_ID).active()).isFalse();
        }

        @Test
        void 상품과_업체가_모두_활성이면_active는_true() {
            given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(product(company(true), true)));

            assertThat(productService.getProductForInternal(PRODUCT_ID).active()).isTrue();
        }
    }

    private static Company company(boolean active) {
        Company company = Company.builder()
                .hubId(UUID.randomUUID())
                .ownerId(OWNER_ID)
                .companyName("부산 수산물 도매")
                .address("부산광역시 중구 중앙대로 1")
                .build();
        company.changeActive(active);
        return company;
    }

    private static Product product(Company company, boolean active) {
        Product product = Product.builder()
                .company(company)
                .productName("고등어")
                .description("냉동")
                .unitPrice(5000L)
                .build();
        product.changeActive(active);
        return product;
    }
}
