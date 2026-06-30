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

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final FileService fileService;

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
                    product.setPrice(productDetails.getPrice());
                    product.setGyu(productDetails.getGyu());
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

        // 1) 대표 이미지: {productId}-{n}.ext 로 정규화
        if (product.getImages() != null && !product.getImages().isEmpty()) {
            java.util.List<String> newUrls = new java.util.ArrayList<>();
            for (int i = 0; i < product.getImages().size(); i++) {
                String url = product.getImages().get(i);
                if (url != null && url.contains("/uploads/")) {
                    try {
                        String encodedFileName = url.substring(url.lastIndexOf("/") + 1);
                        String oldFileName = URLDecoder.decode(encodedFileName, StandardCharsets.UTF_8);

                        String extension = oldFileName.contains(".")
                                ? oldFileName.substring(oldFileName.lastIndexOf("."))
                                : "";
                        String expectedName = product.getId() + "-" + (i + 1) + extension;

                        if (oldFileName.equals(expectedName)) {
                            newUrls.add(url);
                            continue;
                        }

                        fileService.renameFile(oldFileName, expectedName);
                        String newUrl = fileService.getFileUrl(expectedName);
                        renamedUrls.put(url, newUrl);
                        newUrls.add(newUrl);
                        changed = true;
                    } catch (Exception e) {
                        newUrls.add(url);
                    }
                } else {
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
                    productRepository.delete(product);
                    return true;
                })
                .orElse(false);
    }
}
