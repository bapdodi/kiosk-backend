package com.example.demo.service;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.entity.Product;
import com.example.demo.repository.ProductRepository;
import com.example.demo.service.channel.ChannelSyncService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final FileService fileService;
    private final ChannelSyncService channelSyncService;

    public List<Product> getAllProducts() {
        return productRepository.findAllByOrderBySortOrderAscIdAsc();
    }

    public Page<Product> getAllProductsPaged(String mainCategory, String subCategory, Pageable pageable) {
        if (mainCategory == null || mainCategory.isEmpty()) {
            return productRepository.findAllByOrderBySortOrderAscIdAsc(pageable);
        } else if (subCategory == null || subCategory.isEmpty() || subCategory.equals("all")) {
            return productRepository.findByCategoryMain(mainCategory, pageable);
        } else {
            return productRepository.findByCategoryMainAndSub(mainCategory, subCategory, pageable);
        }
    }

    public Optional<Product> getProductById(Long id) {
        return productRepository.findById(id);
    }

    @Transactional
    public Product createProduct(Product product) {
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

                    product.getCombinations().clear();
                    if (productDetails.getCombinations() != null) {
                        for (int i = 0; i < productDetails.getCombinations().size(); i++) {
                            com.example.demo.entity.Combination comb = productDetails.getCombinations().get(i);
                            comb.setProduct(product);
                            comb.setSortOrder(i);
                            product.getCombinations().add(comb);
                        }
                    }

                    renameProductImages(product);
                    return productRepository.save(product);
                });
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
        // 채널에 등록된 상품은 삭제 대신 판매중지로 전환(모든 채널, best-effort, 실패해도 키오스크 삭제는 진행)
        for (Long id : ids) {
            channelSyncService.suspendEverywhere(id);
        }
        List<Product> productsToDelete = productRepository.findAllById(ids);
        productRepository.deleteAll(productsToDelete);
    }

    @Transactional
    public void updateProducts(List<Product> products) {
        for (Product productDetails : products) {
            updateProduct(productDetails.getId(), productDetails);
        }
    }

    @Transactional
    public void updateProductOrders(List<Product> products) {
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
        return productRepository.findById(id)
                .map(product -> {
                    // 채널 등록 상품은 삭제 대신 판매중지(best-effort)
                    channelSyncService.suspendEverywhere(id);
                    productRepository.delete(product);
                    return true;
                })
                .orElse(false);
    }
}
