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
    public Product createProduct(Product product) {
        productCatalogCache.invalidate();
        if (product.getOptionGroups() != null) {
            for (int i = 0; i < product.getOptionGroups().size(); i++) {
                product.getOptionGroups().get(i).setProduct(product);
                product.getOptionGroups().get(i).setSortOrder(i);
            }
        }
        if (product.getCombinations() != null) {
            for (int i = 0; i < product.getCombinations().size(); i++) {
                product.getCombinations().get(i).setProduct(product);
                product.getCombinations().get(i).setSortOrder(i);
            }
        }
        Product saved = productRepository.save(product);
        if (renameProductImages(saved)) {
            return productRepository.save(saved);
        }
        return saved;
    }

    @Transactional
    public Optional<Product> updateProduct(Long id, Product productDetails) {
        productCatalogCache.invalidate();
        return productRepository.findById(id)
                .map(product -> {
                    product.setName(productDetails.getName());
                    product.setDescription(productDetails.getDescription());

                    java.util.Set<com.example.demo.entity.CategoryRef> newCategories = productDetails.getCategories() != null
                            ? productDetails.getCategories()
                            : new java.util.LinkedHashSet<>();

                    boolean categoryChanged = !newCategories.equals(product.getCategories());
                    if (categoryChanged) {
                        product.setIsCategoryModified(true);
                    }

                    product.getCategories().clear();
                    product.getCategories().addAll(newCategories);
                    product.setPriceC(productDetails.getPriceC());
                    product.setGyu(productDetails.getGyu());
                    product.setOriginAreaCode(productDetails.getOriginAreaCode());
                    product.setBrandName(productDetails.getBrandName());
                    product.setHashtags(productDetails.getHashtags());
                    product.setImages(productDetails.getImages());
                    product.setOptionImages(productDetails.getOptionImages());
                    product.setIsComplexOptions(productDetails.getIsComplexOptions());
                    product.setSortOrder(productDetails.getSortOrder());

                    product.getOptionGroups().clear();
                    if (productDetails.getOptionGroups() != null) {
                        for (int i = 0; i < productDetails.getOptionGroups().size(); i++) {
                            com.example.demo.entity.OptionGroup group = productDetails.getOptionGroups().get(i);
                            group.setProduct(product);
                            group.setSortOrder(i);
                            product.getOptionGroups().add(group);
                        }
                    }

                    applyCombinations(product, productDetails.getCombinations());

                    renameProductImages(product);
                    return productRepository.save(product);
                });
    }

    /**
     * 상품 수정 저장 시 규격(조합)을 기존 row 에 맞춰 갱신한다.
     *
     * 예전에는 clear() 후 요청 본문의 인스턴스를 그대로 다시 넣었다. 그러면 id_db 가 없는 조합
     * (수정 화면의 "옵션 조합 생성하기" 가 만드는 형태)이 새 row 로 INSERT 되고, 기존 row 는
     * orphanRemoval 로 물리 삭제됐다. 규격에 걸린 옵션 사진 연결이 끊기고, 숨겨둔 규격까지
     * 휴지통을 거치지 않고 사라진다.
     *
     * 그래서 id_db → ERP 코드 순으로 기존 row 를 찾아 재사용하고, 요청에 없는 기존 조합은
     * 지우는 대신 숨김 처리해 휴지통에서 되살릴 수 있게 남긴다.
     */
    private void applyCombinations(Product product, List<Combination> incoming) {
        List<Combination> existing = new java.util.ArrayList<>(product.getCombinations());

        Map<Long, Combination> byId = new java.util.HashMap<>();
        Map<String, Combination> byErpCode = new java.util.HashMap<>();
        // ERP 코드가 없는 수동 규격은 이름으로만 같은 것인지 알아볼 수 있다.
        // 수정 화면의 "옵션 조합 생성하기" 는 id_db 없는 조합을 만들기 때문에,
        // 이름 매칭이 없으면 저장할 때마다 새 row 가 생기고 옛 row 가 숨김으로 쌓인다.
        Map<String, Combination> byName = new java.util.HashMap<>();
        for (Combination old : existing) {
            if (old.getId_db() != null) {
                byId.put(old.getId_db(), old);
            }
            if (old.getErpCode() != null) {
                byErpCode.putIfAbsent(old.getErpCode(), old);
            } else if (old.getName() != null) {
                byName.putIfAbsent(old.getName(), old);
            }
        }

        List<Combination> next = new java.util.ArrayList<>();
        Set<Long> reused = new LinkedHashSet<>();

        if (incoming != null) {
            for (int i = 0; i < incoming.size(); i++) {
                Combination in = incoming.get(i);

                Combination target = in.getId_db() != null ? byId.get(in.getId_db()) : null;
                if (target == null && in.getErpCode() != null) {
                    target = byErpCode.get(in.getErpCode());
                }
                if (target == null && in.getErpCode() == null && in.getName() != null) {
                    target = byName.get(in.getName());
                }
                // 이미 다른 행에 배정된 기존 row 는 재사용하지 않는다(중복 ERP 코드 방어).
                if (target != null && target.getId_db() != null && !reused.add(target.getId_db())) {
                    target = null;
                }

                if (target != null) {
                    target.setName(in.getName());
                    target.setPriceC(in.getPriceC());
                    target.setPriceA(in.getPriceA());
                    target.setPriceB(in.getPriceB());
                    target.setErpCode(in.getErpCode());
                    target.setStock(in.getStock());
                    target.setId(in.getId());
                    target.setDeleted(Boolean.TRUE.equals(in.getDeleted()));
                    target.setSortOrder(i);
                    next.add(target);
                } else {
                    in.setProduct(product);
                    in.setSortOrder(i);
                    in.setDeleted(Boolean.TRUE.equals(in.getDeleted()));
                    next.add(in);
                }
            }
        }

        // 요청 본문에 없는 기존 조합은 물리 삭제하지 않고 숨김으로 남긴다.
        int tail = next.size();
        for (Combination old : existing) {
            if (old.getId_db() == null || reused.contains(old.getId_db())) {
                continue;
            }
            old.setDeleted(true);
            old.setSortOrder(tail++);
            next.add(old);
        }

        product.getCombinations().clear();
        product.getCombinations().addAll(next);
    }

    private boolean renameProductImages(Product product) {
        boolean changed = false;
        // 같은 원본 파일을 대표/옵션이 공유할 때 동일한 새 URL 로 매핑하기 위한 캐시.
        java.util.Map<String, String> renamedUrls = new java.util.HashMap<>();

        // 1) 대표 이미지: {productId}-{n}.ext 로 정규화.
        //    사진 순서를 바꾸면 목표 이름이 다른 사진이 지금 쓰고 있는 이름과 겹친다.
        //    (예: [3,1,2] 로 재정렬하면 1-3 → 1-1 이 기존 1-1 을 덮어쓴다)
        //    renameFile 은 copy + remove 라 덮어쓰기가 곧 사진 소실이므로,
        //    이름이 바뀌어야 하는 파일을 먼저 고유 임시 이름으로 피신시킨 뒤(1단계)
        //    최종 이름으로 옮긴다(2단계).
        if (product.getImages() != null && !product.getImages().isEmpty()) {
            java.util.List<String> images = product.getImages();
            int n = images.size();
            String[] oldNames = new String[n];
            String[] targets = new String[n];
            String[] temps = new String[n];

            for (int i = 0; i < n; i++) {
                String url = images.get(i);
                if (url == null || !url.contains("/uploads/")) {
                    continue;
                }
                try {
                    String encodedFileName = url.substring(url.lastIndexOf("/") + 1);
                    String oldFileName = URLDecoder.decode(encodedFileName, StandardCharsets.UTF_8);
                    String extension = oldFileName.contains(".")
                            ? oldFileName.substring(oldFileName.lastIndexOf("."))
                            : "";
                    oldNames[i] = oldFileName;
                    targets[i] = product.getId() + "-" + (i + 1) + extension;
                } catch (Exception e) {
                    oldNames[i] = null;
                    targets[i] = null;
                }
            }

            // 1단계: 이름이 달라져야 하는 파일만 임시 이름으로 옮겨 충돌을 없앤다.
            // 이미 제 이름인 파일은 그대로 둔다(목표 이름은 인덱스마다 달라 서로 겹치지 않는다).
            for (int i = 0; i < n; i++) {
                if (oldNames[i] == null || oldNames[i].equals(targets[i])) {
                    continue;
                }
                String tmpName = "tmp-" + java.util.UUID.randomUUID() + "-" + targets[i];
                try {
                    fileService.renameFile(oldNames[i], tmpName);
                    temps[i] = tmpName;
                } catch (Exception e) {
                    temps[i] = null; // 실패 시 기존 이름을 그대로 유지한다
                }
            }

            // 2단계: 임시 이름 → 최종 이름.
            java.util.List<String> newUrls = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                String url = images.get(i);
                if (oldNames[i] == null || oldNames[i].equals(targets[i]) || temps[i] == null) {
                    newUrls.add(url);
                    continue;
                }
                try {
                    fileService.renameFile(temps[i], targets[i]);
                    String newUrl = fileService.getFileUrl(targets[i]);
                    renamedUrls.put(url, newUrl);
                    newUrls.add(newUrl);
                    changed = true;
                } catch (Exception e) {
                    // 최종 이동 실패: 임시 이름에 파일이 갇히지 않도록 원래 이름으로 되돌린다.
                    try {
                        fileService.renameFile(temps[i], oldNames[i]);
                    } catch (Exception ignored) {
                        // 되돌리기까지 실패하면 임시 이름에 남는다(로그 대신 URL 은 원본 유지).
                    }
                    newUrls.add(url);
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
    public void updateProducts(List<Product> products) {
        productCatalogCache.invalidate();
        for (Product productDetails : products) {
            updateProduct(productDetails.getId(), productDetails);
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
