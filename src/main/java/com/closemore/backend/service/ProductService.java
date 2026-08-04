package com.closemore.backend.service;

import com.closemore.backend.domain.ProductEntity;
import com.closemore.backend.dto.PageResponse;
import com.closemore.backend.dto.ProductCreateRequest;
import com.closemore.backend.dto.ProductResponse;
import com.closemore.backend.dto.ProductUpdateRequest;
import com.closemore.backend.mapper.DtoMapper;
import com.closemore.backend.rbac.AuthenticatedUser;
import com.closemore.backend.rbac.CurrentUserService;
import com.closemore.backend.rbac.RbacService;
import com.closemore.backend.rbac.Role;
import com.closemore.backend.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The product catalogue - five endpoints.
 *
 * <p><b>Products are GLOBAL reference data with no RLS policy.</b> Every organisation reads and
 * writes the same rows; there is no Organization_Name column to scope by. So unlike every resource
 * group before this one, the Admin check is not defence in depth behind a policy - it is the ONLY
 * thing standing between a caller and the shared catalogue. If it is ever removed, nothing at the
 * database layer will catch it. See ProductEntity, and the open item in docs/HANDOFF.md.
 *
 * <p>Reads are open to any authenticated user, matching v5 and the deal line-item flow, which needs
 * the catalogue to price anything.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class ProductService {

    private static final String OBJECT_TYPE = "Product";

    private static final Set<String> SORTABLE_PROPERTIES = Set.of(
            "productId", "name", "skuCode", "type", "unitPrice", "active");

    private final ProductRepository productRepository;
    private final RbacService rbacService;
    private final CurrentUserService currentUserService;
    private final AuditService auditService;

    // --- reads ------------------------------------------------------------------------------

    /**
     * GET /api/v1/products
     *
     * <p>{@code activeOnly} defaults to false - the whole catalogue, including retired products.
     * A retired product still appears on historical line items, so a client rendering an old deal
     * needs to be able to resolve it.
     */
    public List<ProductResponse> listAll(Sort sort, Boolean activeOnly) {
        currentUserService.require();
        requireSortablePropertiesOnly(sort);

        List<ProductEntity> products = Boolean.TRUE.equals(activeOnly)
                ? productRepository.findByActive(true, sort)
                : productRepository.findAll(sort);

        return products.stream().map(DtoMapper::toProductResponse).toList();
    }

    /** GET /api/v1/products?page=... - the opt-in paginated form. */
    public PageResponse<ProductResponse> listPage(Pageable pageable, Boolean activeOnly) {
        currentUserService.require();
        requireSortablePropertiesOnly(pageable.getSort());

        return Boolean.TRUE.equals(activeOnly)
                ? PageResponse.of(productRepository.findByActive(true, pageable),
                        DtoMapper::toProductResponse)
                : PageResponse.of(productRepository.findAll(pageable),
                        DtoMapper::toProductResponse);
    }

    /**
     * GET /api/v1/products/{productId}
     *
     * <p>Not in the JS inventory; added by the same project decision that added it to Contacts.
     * Once a list arrives 25 rows at a time, a line item referencing one product would otherwise
     * have to walk pages to resolve its name.
     */
    public ProductResponse get(String productId) {
        currentUserService.require();
        return DtoMapper.toProductResponse(load(productId));
    }

    // --- writes -----------------------------------------------------------------------------

    /** POST /api/v1/products - Admin only. */
    public ProductResponse create(ProductCreateRequest request) {
        AuthenticatedUser actor = requireAdmin();

        ProductEntity product = new ProductEntity();
        product.setProductId(UUID.randomUUID().toString());
        product.setName(request.name());
        product.setSkuCode(request.skuCode());
        product.setType(request.type());
        product.setUnitPrice(request.unitPrice());
        product.setDescription(request.description());
        // Column default is true, Java's primitive default is false - set it explicitly or every
        // product created without an isActive would be born retired.
        product.setActive(request.isActive() == null || request.isActive());

        ProductEntity saved = productRepository.save(product);
        ProductResponse response = DtoMapper.toProductResponse(saved);
        auditService.logCreate(actor, OBJECT_TYPE, saved.getProductId(), saved.getName(), response);
        return response;
    }

    /** PUT /api/v1/products/{productId} - Admin only. */
    public ProductResponse update(String productId, ProductUpdateRequest request) {
        AuthenticatedUser actor = requireAdmin();
        ProductEntity product = load(productId);
        ProductResponse before = DtoMapper.toProductResponse(product);

        if (request.name() != null) {
            product.setName(request.name());
        }
        if (request.skuCode() != null) {
            product.setSkuCode(request.skuCode());
        }
        if (request.type() != null) {
            product.setType(request.type());
        }
        if (request.unitPrice() != null) {
            product.setUnitPrice(request.unitPrice());
        }
        if (request.description() != null) {
            product.setDescription(request.description());
        }
        if (request.isActive() != null) {
            product.setActive(request.isActive());
        }

        ProductEntity saved = productRepository.save(product);
        ProductResponse after = DtoMapper.toProductResponse(saved);
        auditService.logUpdate(actor, OBJECT_TYPE, productId, saved.getName(), before, after);
        return after;
    }

    /**
     * DELETE /api/v1/products/{productId} - Admin only, and a SOFT delete.
     *
     * <p>v5 is explicit: this sets {@code Is_Active = false} and there is no hard delete. The row
     * has to survive because line_items reference it, and a historical deal that cannot resolve its
     * own products is a corrupted audit trail rather than a tidy database.
     *
     * <p>Logged as an UPDATE, not a DELETE, because that is what it is - and because logDelete
     * records the before-state as "the only surviving copy of the row", which would be a lie here.
     */
    public void softDelete(String productId) {
        AuthenticatedUser actor = requireAdmin();
        ProductEntity product = load(productId);
        ProductResponse before = DtoMapper.toProductResponse(product);

        product.setActive(false);

        ProductEntity saved = productRepository.save(product);
        auditService.logUpdate(actor, OBJECT_TYPE, productId, saved.getName(),
                before, DtoMapper.toProductResponse(saved));
    }

    // --- helpers ----------------------------------------------------------------------------

    private AuthenticatedUser requireAdmin() {
        AuthenticatedUser actor = currentUserService.require();
        rbacService.requireRole(actor, Role.ADMIN);
        return actor;
    }

    private ProductEntity load(String productId) {
        return productRepository.findById(productId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Product " + productId + " not found"));
    }

    private static void requireSortablePropertiesOnly(Sort sort) {
        for (Sort.Order order : sort) {
            if (!SORTABLE_PROPERTIES.contains(order.getProperty())) {
                throw new BadRequestException("Cannot sort by '" + order.getProperty() + "'");
            }
        }
    }
}
