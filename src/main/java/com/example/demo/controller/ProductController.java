package com.example.demo.controller;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
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

import com.example.demo.entity.Product;
import com.example.demo.service.ProductCatalogCache;
import com.example.demo.service.ProductService;
import com.example.demo.service.PublicProductJson;
import com.fasterxml.jackson.databind.JsonNode;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;
    private final ProductCatalogCache productCatalogCache;
    private final PublicProductJson publicProductJson;

    /**
     * 손님 화면용 조회 API 는 단가를 빼고 내려준다. 화면에서 가격을 감춰도 응답 본문에 남으면
     * 누구나 그대로 읽을 수 있고, 주문 금액은 서버가 다시 계산하므로 손님 단말에 줄 이유가 없다.
     * 가격이 필요한 관리자 화면은 아래 /admin 경로(ROLE_ADMIN)를 쓴다.
     */
    @GetMapping
    public JsonNode getAllProducts(
            @RequestParam(name = "mainCategory", required = false) String mainCategory,
            @RequestParam(name = "subCategory", required = false) String subCategory,
            @PageableDefault(size = 50, sort = "sortOrder", direction = Sort.Direction.ASC) Pageable pageable) {
        Page<Product> page = productService.getAllProductsPaged(mainCategory, subCategory, pageable);
        return publicProductJson.strip(page);
    }

    /**
     * 전체 상품을 한 번에 내려준다. 상품이 1천 건 남짓이라 화면에서 카테고리·검색을 모두
     * 처리할 수 있고, 응답은 캐싱돼 있어 매번 DB 를 다시 읽지 않는다.
     */
    @GetMapping(value = "/all", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getAllProductsAtOnce() {
        return ResponseEntity.ok(productCatalogCache.getPublicCatalogJson());
    }

    /** 관리자 화면용 전체 상품 목록. 단가를 포함한 원본이라 ROLE_ADMIN 이 필요하다. */
    @GetMapping(value = "/admin/all", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getAllProductsForAdmin() {
        return ResponseEntity.ok(productCatalogCache.getCatalogJson());
    }

    @GetMapping("/{id}")
    public ResponseEntity<JsonNode> getProductById(@PathVariable("id") Long id) {
        return productService.getProductById(id)
                .map(product -> ResponseEntity.ok(publicProductJson.strip(product)))
                .orElse(ResponseEntity.notFound().build());
    }

    // 상품은 ERP 동기화로만 등록한다. 수동 생성 API는 의도적으로 비활성화한다.
    // @PostMapping("/admin")
    // public Product createProduct(@RequestBody Product product) {
    //     return productService.createProduct(product);
    // }

    @PutMapping("/admin/{id}")
    public ResponseEntity<Product> updateProduct(@PathVariable("id") Long id, @RequestBody Product productDetails) {
        return productService.updateProduct(id, productDetails)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/admin/{id}")
    public ResponseEntity<Void> deleteProduct(@PathVariable("id") Long id) {
        if (productService.deleteProduct(id)) {
            return ResponseEntity.ok().build();
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/admin/bulk-delete")
    public ResponseEntity<Void> deleteProducts(@RequestBody List<Long> ids) {
        productService.deleteProducts(ids);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/admin/trash")
    public List<Product> getTrash() {
        return productService.getDeletedProducts();
    }

    @PostMapping("/admin/{id}/restore")
    public ResponseEntity<Product> restoreProduct(@PathVariable("id") Long id) {
        return productService.restoreProduct(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/admin/{id}/permanent")
    public ResponseEntity<Void> permanentlyDeleteProduct(@PathVariable("id") Long id) {
        return switch (productService.permanentlyDeleteProduct(id)) {
            case DELETED -> ResponseEntity.noContent().build();
            case NOT_FOUND -> ResponseEntity.notFound().build();
            case TOO_EARLY -> ResponseEntity.status(409).build();
        };
    }

    @PutMapping("/admin/bulk-update")
    public ResponseEntity<Void> updateProducts(@RequestBody List<Product> products) {
        productService.updateProducts(products);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/admin/reorder")
    public ResponseEntity<Void> reorderProducts(@RequestBody List<Product> products) {
        productService.updateProductOrders(products);
        return ResponseEntity.ok().build();
    }
}
