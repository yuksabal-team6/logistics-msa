package com.yuksabal.company.product.service;

import com.yuksabal.common.exception.BusinessException;
import com.yuksabal.company.company.entity.Company;
import com.yuksabal.company.company.service.CompanyService;
import com.yuksabal.company.global.auth.OwnerPermission;
import com.yuksabal.company.global.auth.UserRole;
import com.yuksabal.company.product.dto.request.ProductCreateRequest;
import com.yuksabal.company.product.dto.response.ProductResponse;
import com.yuksabal.company.product.entity.Product;
import com.yuksabal.company.product.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static com.yuksabal.common.exception.CommonErrorCode.RESOURCE_NOT_FOUND;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductService {

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

    // 삭제된 상품은 @SQLRestriction으로 조회되지 않아 없는 상품과 같이 404. 조회·수정·삭제 공용
    private Product findProduct(UUID productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new BusinessException(
                        RESOURCE_NOT_FOUND,
                        "존재하지 않거나 삭제된 상품입니다.",
                        "productId"));
    }
}
