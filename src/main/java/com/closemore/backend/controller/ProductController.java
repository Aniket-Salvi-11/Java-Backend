package com.closemore.backend.controller;

import com.closemore.backend.dto.ProductCreateRequest;
import com.closemore.backend.dto.ProductResponse;
import com.closemore.backend.dto.ProductUpdateRequest;
import com.closemore.backend.service.ProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The product catalogue. Reads are open to any authenticated user; writes are Admin only.
 *
 * <p>Products are global reference data with no RLS policy, so that Admin check is the only thing
 * protecting the shared catalogue - see ProductService.
 */
@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    /**
     * GET /api/v1/products
     *
     * <p>Bare array by default, envelope on {@code ?page=} or {@code ?size=}. Default sort is
     * {@code name} then {@code productId} - the second key makes the ordering total, without which
     * a product with a duplicate name could appear on two pages or none.
     */
    @GetMapping
    public Object list(
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size,
            @RequestParam(name = "activeOnly", required = false) Boolean activeOnly,
            @PageableDefault(size = 25,
                    sort = {"name", "productId"},
                    direction = Sort.Direction.ASC) Pageable pageable) {

        boolean paginationRequested = page != null || size != null;

        return paginationRequested
                ? productService.listPage(pageable, activeOnly)
                : productService.listAll(pageable.getSort(), activeOnly);
    }

    /** GET /api/v1/products/{productId} */
    @GetMapping("/{productId}")
    public ProductResponse get(@PathVariable String productId) {
        return productService.get(productId);
    }

    /** POST /api/v1/products - Admin only. */
    @PostMapping
    public ResponseEntity<ProductResponse> create(
            @Valid @RequestBody ProductCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(productService.create(request));
    }

    /** PUT /api/v1/products/{productId} - Admin only. */
    @PutMapping("/{productId}")
    public ProductResponse update(@PathVariable String productId,
                                  @Valid @RequestBody ProductUpdateRequest request) {
        return productService.update(productId, request);
    }

    /**
     * DELETE /api/v1/products/{productId} - Admin only, and a SOFT delete.
     *
     * <p>204 with no body, like every other delete here, even though the row survives with
     * {@code Is_Active = false}. The status describes the caller's view: the product is gone from
     * the active catalogue. Historical line items can still resolve it.
     */
    @DeleteMapping("/{productId}")
    public ResponseEntity<Void> delete(@PathVariable String productId) {
        productService.softDelete(productId);
        return ResponseEntity.noContent().build();
    }
}
