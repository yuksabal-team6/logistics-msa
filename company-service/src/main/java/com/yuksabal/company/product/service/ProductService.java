package com.yuksabal.company.product.service;

import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.response.PageResponse;
import com.yuksabal.company.company.entity.Company;
import com.yuksabal.company.company.service.CompanyService;
import com.yuksabal.company.global.auth.OwnerPermission;
import com.yuksabal.company.global.auth.UserRole;
import com.yuksabal.company.product.dto.request.ProductCreateRequest;
import com.yuksabal.company.product.dto.request.ProductSearchRequest;
import com.yuksabal.company.product.dto.response.ProductInternalResponse;
import com.yuksabal.company.product.dto.response.ProductResponse;
import com.yuksabal.company.product.entity.Product;
import com.yuksabal.company.product.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

import static com.yuksabal.common.exception.CommonErrorCode.RESOURCE_NOT_FOUND;
import static com.yuksabal.company.global.util.PageableUtils.toStablePageable;
import static com.yuksabal.company.product.repository.ProductSpecification.companyIdEquals;
import static com.yuksabal.company.product.repository.ProductSpecification.hubIdEquals;
import static com.yuksabal.company.product.repository.ProductSpecification.productNameContains;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductService {

    // 목록 정렬 허용 필드 (API 명세 0.4: 정렬 필드는 API별로 제한)
    private static final Set<String> SORTABLE_FIELDS = Set.of("createdAt", "productName", "unitPrice");

    private final ProductRepository productRepository;
    private final CompanyService companyService;

    // 소속 업체를 먼저 찾아야 관리자를 알 수 있어 업체 조회 → 권한 순서
    @Transactional
    public ProductResponse createProduct(UUID companyId, ProductCreateRequest request, UUID userId, UserRole role) {
        Company company = companyService.findCompany(companyId);
        OwnerPermission.validate(company.getOwnerId(), userId, role);

        Product product = Product.builder()
                .company(company)
                .productName(request.productName())
                .description(request.description())
                .unitPrice(request.unitPrice())
                .build();

        return ProductResponse.from(productRepository.save(product));
    }

    public ProductResponse getProduct(UUID productId) {
        return ProductResponse.from(findProduct(productId));
    }

    // 응답에는 업체 ID만 담아 상품 수만큼 업체를 조회하는 N+1이 생기지 않는다
    public PageResponse<ProductResponse> getProducts(ProductSearchRequest request, Pageable pageable) {
        Specification<Product> spec = Specification
                .where(productNameContains(request.productName()))
                .and(companyIdEquals(request.companyId()))
                .and(hubIdEquals(request.hubId()));

        Page<Product> products = productRepository.findAll(spec, toStablePageable(pageable, SORTABLE_FIELDS));

        return PageResponse.from(products, ProductResponse::from);
    }

    // 내부 API #1 전용. 외부 단건 조회와 같은 404 규칙, 응답만 내부 계약(ProductInternalResponse)
    public ProductInternalResponse getProductForInternal(UUID productId) {
        return ProductInternalResponse.from(findProduct(productId));
    }

    // 삭제된 상품은 @SQLRestriction으로 조회되지 않아 없는 상품과 같이 404. 조회·수정·삭제 공용
    private Product findProduct(UUID productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new BusinessException(
                        RESOURCE_NOT_FOUND,
                        "존재하지 않거나 삭제된 상품입니다.",
                        "productId"));
    }
}
