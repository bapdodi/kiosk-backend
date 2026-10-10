package com.example.demo.service;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.dto.ProductUpdateRequest;
import com.example.demo.dto.ProductUpdateRequest.CombinationDisplayUpdate;
import com.example.demo.entity.Combination;
import com.example.demo.entity.Product;
import com.example.demo.repository.CombinationRepository;
import com.example.demo.repository.ProductRepository;
import com.example.demo.service.channel.ChannelSyncService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final CombinationRepository combinationRepository;
    private final FileService fileService;
    private final ChannelSyncService channelSyncService;
    private final ProductCatalogCache productCatalogCache;

    public List<Product> getAllProducts() {
        return productRepository.findAllByDeletedAtIsNullOrderBySortOrderAscIdAsc();
    }

    public Page<Product> getAllProductsPaged(String mainCategory, String subCategory, Pageable pageable) {
        if (mainCategory == null || mainCategory.isEmpty()) {
            return productRepository.findAllByDeletedAtIsNullOrderBySortOrderAscIdAsc(pageable);
        } else if (subCategory == null || subCategory.isEmpty() || subCategory.equals("all")) {
            return productRepository.findByCategoryMain(mainCategory, pageable);
        } else {
            return productRepository.findByCategoryMainAndSub(mainCategory, subCategory, pageable);
        }
    }

    public Optional<Product> getProductById(Long id) {
        return productRepository.findByIdAndDeletedAtIsNull(id);
    }

    @Transactional
    public Optional<Product> updateProduct(Long id, ProductUpdateRequest details) {
        productCatalogCache.invalidate();
        return productRepository.findById(id).map(product -> {
            if (details.name() != null && !details.name().isBlank()) product.setName(details.name().trim());
            if (details.description() != null) product.setDescription(details.description());
            if (details.categories() != null) {
                if (!details.categories().equals(product.getCategories())) {
                    product.setIsCategoryModified(true);
                }
                product.getCategories().clear();
                product.getCategories().addAll(details.categories());
            }
            if (details.hashtags() != null) product.setHashtags(details.hashtags());
            if (details.images() != null) product.setImages(details.images());
            if (details.optionImages() != null) product.setOptionImages(details.optionImages());
            if (details.sortOrder() != null) product.setSortOrder(details.sortOrder());
            applyCombinationDisplay(product, details.combinations());
            renameProductImages(product);
            return productRepository.save(product);
        });
    }

    /** 기존 규격의 표시 설정만 수정한다. 요청에 없는 규격은 그대로 보존한다. */
    private void applyCombinationDisplay(Product product, List<CombinationDisplayUpdate> incoming) {
        if (incoming == null || product.getCombinations() == null) return;
        Map<Long, Combination> byId = new java.util.HashMap<>();
        for (Combination combo : product.getCombinations()) {
            if (combo.getId_db() != null) byId.put(combo.getId_db(), combo);
        }
        Set<Long> updated = new LinkedHashSet<>();
        for (CombinationDisplayUpdate settings : incoming) {
            Combination target = byId.get(settings.id_db());
            if (target == null || !updated.add(settings.id_db())) continue;
            if (settings.deleted() != null) target.setDeleted(settings.deleted());
            if (settings.sortOrder() != null) target.setSortOrder(settings.sortOrder());
            if (settings.kioskName() != null) {
                // 빈 문자열은 "ERP 규격명으로 되돌리기".
                String kioskName = settings.kioskName().trim();
                target.setKioskName(kioskName.isEmpty() ? null : kioskName);
            }
        }
    }

    private boolean renameProductImages(Product product) {
        boolean changed = false;
        // 같은 원본 파일을 대표/옵션이 공유할 때 동일한 새 URL 로 매핑하기 위한 캐시.
        java.util.Map<String, String> renamedUrls = new java.util.HashMap<>();

        // 1) 대표 이미지: {productId}-{n}-{랜덤8자}.ext 로 정규화.
        //    /uploads/ 응답은 Cloudflare·브라우저에 캐시되므로 파일명이 같으면 사진을 바꿔도 옛 사진이 보인다.
        //    (순서를 바꾸면 한 칸은 옛 캐시, 한 칸은 새 파일이라 사진이 복사된 것처럼 보이기도 한다)
        //    그래서 제 자리에 있는 사진은 이름을 유지하고, 바뀐 사진은 매번 새 이름을 받아 URL 이 달라지게 한다.
        //    목표 이름이 항상 새 이름이라 다른 사진과 겹칠 일이 없다.
        if (product.getImages() != null && !product.getImages().isEmpty()) {
            java.util.List<String> images = product.getImages();
            java.util.List<String> newUrls = new java.util.ArrayList<>();

            for (int i = 0; i < images.size(); i++) {
                String url = images.get(i);
                if (url == null || !url.contains("/uploads/")) {
                    newUrls.add(url);
                    continue;
                }
                if (renamedUrls.containsKey(url)) {
                    // 같은 파일이 대표 이미지에 두 번 들어 있으면 먼저 옮긴 새 URL 로 맞춘다.
                    newUrls.add(renamedUrls.get(url));
                    continue;
                }
                try {
                    String encodedFileName = url.substring(url.lastIndexOf("/") + 1);
                    String oldFileName = URLDecoder.decode(encodedFileName, StandardCharsets.UTF_8);
                    if (isInPlace(oldFileName, product.getId(), i + 1)) {
                        newUrls.add(url);
                        continue;
                    }
                    String extension = oldFileName.contains(".")
                            ? oldFileName.substring(oldFileName.lastIndexOf("."))
                            : "";
                    String target = product.getId() + "-" + (i + 1) + "-"
                            + java.util.UUID.randomUUID().toString().substring(0, 8) + extension;
                    // 원본이 없으면 renameFile 이 옛 이름을 돌려준다 — 없는 파일을 가리키지 않도록 URL 을 유지한다.
                    if (!target.equals(fileService.renameFile(oldFileName, target))) {
                        newUrls.add(url);
                        continue;
                    }
                    String newUrl = fileService.getFileUrl(target);
                    renamedUrls.put(url, newUrl);
                    newUrls.add(newUrl);
                    changed = true;
                } catch (Exception e) {
                    newUrls.add(url); // 실패 시 기존 이름을 그대로 유지한다
                }
            }
            product.setImages(newUrls);
        }

        // 2) 옵션 이미지: 한글 등 비-ASCII 파일명만 ASCII 로 정규화한다.
        //    운영 서버가 한글 파일명을 서빙할 때 500 이 나므로 안전한 이름으로 바꾼다.
        //    이미 ASCII 인 경우(대표 이미지 공유 등)는 건드리지 않아 공유가 깨지지 않게 한다.
        if (product.getOptionImages() != null && !product.getOptionImages().isEmpty()) {
            for (com.example.demo.entity.OptionImage oi : product.getOptionImages()) {
                String url = oi.getImageUrl();
                if (url == null || !url.contains("/uploads/"))
                    continue;
                // 이번 저장에서 이미 옮긴 동일 원본 파일이면 같은 새 URL 로 맞춘다.
                if (renamedUrls.containsKey(url)) {
                    oi.setImageUrl(renamedUrls.get(url));
                    changed = true;
                    continue;
                }
                try {
                    String encodedFileName = url.substring(url.lastIndexOf("/") + 1);
                    String oldFileName = URLDecoder.decode(encodedFileName, StandardCharsets.UTF_8);

                    // ASCII 외 문자(한글 등)나 공백을 제거해 안전한 이름을 만든다. UUID 접두사는 보존되어 유일성 유지.
                    String safeName = oldFileName.replaceAll("[^A-Za-z0-9._-]", "");
                    if (safeName.isEmpty() || safeName.equals(oldFileName)) {
                        // 이미 안전한 이름이면 그대로 둔다.
                        continue;
                    }

                    fileService.renameFile(oldFileName, safeName);
                    String newUrl = fileService.getFileUrl(safeName);
                    renamedUrls.put(url, newUrl);
                    oi.setImageUrl(newUrl);
                    changed = true;
                } catch (Exception e) {
                    // 실패 시 기존 URL 유지
                }
            }
        }

        return changed;
    }

    /** 이미 {productId}-{n}.ext 또는 {productId}-{n}-{8자}.ext 이름이면 그 자리의 사진이다. */
    private static boolean isInPlace(String fileName, Long productId, int position) {
        return fileName.matches("^" + productId + "-" + position + "(-[0-9a-f]{8})?(\\.[^.]*)?$");
    }

    @Transactional
    public void deleteProducts(List<Long> ids) {
        productCatalogCache.invalidate();
        // 외부 판매 채널은 즉시 판매중지하고, 키오스크 상품은 30일간 휴지통에 보존한다.
        Instant now = Instant.now();
        for (Long id : ids) {
            channelSyncService.suspendEverywhere(id);
        }
        List<Product> productsToDelete = productRepository.findAllById(ids).stream()
                .filter(product -> product.getDeletedAt() == null)
                .toList();
        productsToDelete.forEach(product -> {
            product.setDeletedAt(now);
            product.setDeletedBy("admin");
        });
        productRepository.saveAll(productsToDelete);
    }

    /** ERP 에서 사라진 품목 정리 결과. */
    public record ErpRemovalResult(int trashedProducts, int hiddenOptions) {}

    /**
     * ERP 에서 빠진 품목코드에 걸린 키오스크 상품을 정리한다.
     *
     * 규격별 옵션으로 들어가 있으면 그 옵션만 숨기고, 그렇게 해서 남은 옵션이 하나도 없거나
     * 상품 자체가 그 코드로 묶여 있으면 상품을 휴지통으로 옮긴다(30일 보존 + 외부 채널 판매중지).
     */
    @Transactional
    public ErpRemovalResult trashByErpCodes(Collection<String> erpCodes) {
        if (erpCodes == null || erpCodes.isEmpty()) {
            return new ErpRemovalResult(0, 0);
        }
        productCatalogCache.invalidate();

        Set<Product> touched = new LinkedHashSet<>();
        Set<Long> toTrash = new LinkedHashSet<>();
        int hiddenOptions = 0;

        for (String erpCode : erpCodes) {
            productRepository.findByErpCode(erpCode)
                    .filter(product -> product.getDeletedAt() == null)
                    .ifPresent(product -> toTrash.add(product.getId()));

            for (Product product : productRepository.findByCombinationErpCode(erpCode)) {
                for (Combination combination : product.getCombinations()) {
                    if (erpCode.equals(combination.getErpCode()) && !Boolean.TRUE.equals(combination.getDeleted())) {
                        combination.setDeleted(true);
                        hiddenOptions++;
                    }
                }
                touched.add(product);
            }
        }
        productRepository.saveAll(touched);

        // 옵션이 전부 숨겨진 상품은 살아 있어도 팔 수 있는 규격이 없으므로 같이 휴지통으로 보낸다.
        for (Product product : touched) {
            if (product.getDeletedAt() != null) continue;
            boolean anyAlive = product.getCombinations().stream()
                    .anyMatch(combination -> !Boolean.TRUE.equals(combination.getDeleted()));
            if (!anyAlive) {
                toTrash.add(product.getId());
            }
        }
        if (!toTrash.isEmpty()) {
            deleteProducts(List.copyOf(toTrash));
        }
        return new ErpRemovalResult(toTrash.size(), hiddenOptions);
    }

    @Transactional
    public void updateProducts(List<ProductUpdateRequest> products) {
        productCatalogCache.invalidate();
        for (ProductUpdateRequest productDetails : products) {
            updateProduct(productDetails.id(), productDetails);
        }
    }

    @Transactional
    public void updateProductOrders(List<Product> products) {
        productCatalogCache.invalidate();
        Map<Long, String> orderMap = products.stream()
                .collect(java.util.stream.Collectors.toMap(Product::getId, Product::getSortOrder));
        List<Product> existing = productRepository.findAllById(orderMap.keySet());
        existing.forEach(p -> {
            String newOrder = orderMap.get(p.getId());
            if (newOrder != null) p.setSortOrder(newOrder);
        });
        productRepository.saveAll(existing);
    }

    @Transactional
    public boolean deleteProduct(Long id) {
        productCatalogCache.invalidate();
        return productRepository.findByIdAndDeletedAtIsNull(id)
                .map(product -> {
                    channelSyncService.suspendEverywhere(id);
                    product.setDeletedAt(Instant.now());
                    product.setDeletedBy("admin");
                    productRepository.save(product);
                    return true;
                })
                .orElse(false);
    }

    public List<Product> getDeletedProducts() {
        return productRepository.findAllByDeletedAtIsNotNullOrderByDeletedAtDesc();
    }

    @Transactional
    public Optional<Product> restoreProduct(Long id) {
        productCatalogCache.invalidate();
        return productRepository.findByIdAndDeletedAtIsNotNull(id)
                .map(product -> {
                    product.setDeletedAt(null);
                    product.setDeletedBy(null);
                    return productRepository.save(product);
                });
    }

    /**
     * 휴지통에 함께 보여줄 "숨겨진 규격" 한 줄.
     *
     * ERP 백업 반영 등으로 조합만 숨겨진 경우, 예전에는 어느 화면에서도 보이지 않아
     * 상품에서 규격이 조용히 사라진 것처럼 보였다. 휴지통에서 그대로 되살릴 수 있게 한다.
     */
    public record HiddenOption(Long id, Long productId, String productName,
            String optionName, String erpCode, Integer priceC, Integer stock) {}

    @Transactional(readOnly = true)
    public List<HiddenOption> getHiddenOptions() {
        return combinationRepository.findHiddenOptions().stream()
                .map(c -> new HiddenOption(c.getId_db(), c.getProduct().getId(), c.getProduct().getName(),
                        c.getName(), c.getErpCode(), c.getPriceC(), c.getStock()))
                .toList();
    }

    /** 숨겨진 규격을 다시 노출한다. 대상이 없으면 false. */
    @Transactional
    public boolean restoreHiddenOption(Long id) {
        return combinationRepository.findById(id)
                .filter(c -> Boolean.TRUE.equals(c.getDeleted()))
                .map(c -> {
                    c.setDeleted(false);
                    combinationRepository.save(c);
                    productCatalogCache.invalidate();
                    return true;
                })
                .orElse(false);
    }

    // 영구 삭제는 의도적으로 제공하지 않는다.
    // 휴지통은 되돌릴 수 있는 보관함이어야 하고, 자동 만료도 없다(스케줄러 없음).
    // 실수로 지운 상품/규격이 복구 불가능해지는 경로를 아예 만들지 않는다.
}
