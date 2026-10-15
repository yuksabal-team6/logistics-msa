package com.yuksabal.company.product.service;

import com.yuksabal.common.auth.UserRole;
import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.common.response.PageResponse;
import com.yuksabal.company.company.entity.Company;
import com.yuksabal.company.company.repository.CompanyRepository;
import com.yuksabal.company.global.auth.OwnerPermission;
import com.yuksabal.company.product.dto.request.ProductCreateRequest;
import com.yuksabal.company.product.dto.request.ProductSearchRequest;
import com.yuksabal.company.product.dto.request.ProductUpdateRequest;
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
import static com.yuksabal.common.util.PageableUtils.toStablePageable;
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
    private final CompanyRepository companyRepository;

    // 소속 업체를 먼저 찾아야 관리자를 알 수 있어 업체 조회 → 권한 순서
    @Transactional
    public ProductResponse createProduct(UUID companyId, ProductCreateRequest request, UUID userId, UserRole role) {
        Company company = findCompany(companyId);
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

    // 권한은 상품의 소속 업체 관리자 기준이라 상품 조회 → 권한 순서
    @Transactional
    public ProductResponse updateProduct(UUID productId, ProductUpdateRequest request, UUID userId, UserRole role) {
        Product product = findProduct(productId);
        OwnerPermission.validate(product.getCompany().getOwnerId(), userId, role);

        product.updateInfo(request.productName(), request.description(), request.unitPrice());

        // 변경 감지는 커밋 때 UPDATE하므로, 응답의 updatedAt을 채우려고 먼저 반영한다
        productRepository.flush();
        return ProductResponse.from(product);
    }

    // Soft Delete라 실제로는 deleted_at·deleted_by를 기록하는 UPDATE
    @Transactional
    public void deleteProduct(UUID productId, UUID userId, UserRole role) {
        Product product = findProduct(productId);
        OwnerPermission.validate(product.getCompany().getOwnerId(), userId, role);

        product.delete(userId);
    }

    // 업체 삭제 전용. 권한(MASTER)은 업체 삭제에서 이미 확인했으므로 여기서는 검사하지 않는다
    @Transactional
    public void deleteAllByCompany(UUID companyId, UUID userId) {
        productRepository.findAllByCompanyId(companyId)
                .forEach(product -> product.delete(userId));
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

    // 상품 생성 시 소속 업체 조회. CompanyService가 업체 삭제 때 ProductService를 쓰므로, 순환 의존을 피하려고 Repository로 읽기만 한다
    // 삭제된 업체는 @SQLRestriction으로 조회되지 않아 없는 업체와 같이 404
    private Company findCompany(UUID companyId) {
        return companyRepository.findById(companyId)
                .orElseThrow(() -> new BusinessException(
                        RESOURCE_NOT_FOUND,
                        "존재하지 않거나 삭제된 업체입니다.",
                        "companyId"));
    }
}
