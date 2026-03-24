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
            return productRepository.findAllByMainCategoryOrderBySortOrderAscIdAsc(mainCategory, pageable);
        } else {
            return productRepository.findAllByMainCategoryAndSubCategoryOrderBySortOrderAscIdAsc(mainCategory,
                    subCategory, pageable);
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
                    boolean categoryChanged = !productDetails.getMainCategory().equals(product.getMainCategory())
                            || !productDetails.getSubCategory().equals(product.getSubCategory());

                    if (categoryChanged) {
                        product.setIsCategoryModified(true);
                    }

                    product.setMainCategory(productDetails.getMainCategory());
                    product.setSubCategory(productDetails.getSubCategory());
                    product.setPrice(productDetails.getPrice());
                    product.setHashtags(productDetails.getHashtags());
                    product.setImages(productDetails.getImages());
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
        if (product.getImages() == null || product.getImages().isEmpty())
            return false;

        java.util.List<String> newUrls = new java.util.ArrayList<>();
        boolean changed = false;

        for (int i = 0; i < product.getImages().size(); i++) {
            String url = product.getImages().get(i);
            if (url != null && url.contains("/uploads/")) {
                try {
                    String encodedFileName = url.substring(url.lastIndexOf("/") + 1);
                    String oldFileName = URLDecoder.decode(encodedFileName, StandardCharsets.UTF_8);

                    String extension = oldFileName.contains(".") ? oldFileName.substring(oldFileName.lastIndexOf("."))
                            : "";
                    String expectedName = product.getId() + "-" + (i + 1) + extension;

                    if (oldFileName.equals(expectedName)) {
                        newUrls.add(url);
                        continue;
                    }

                    fileService.renameFile(oldFileName, expectedName);
                    newUrls.add(fileService.getFileUrl(expectedName));
                    changed = true;
                } catch (Exception e) {
                    newUrls.add(url);
                }
            } else {
                newUrls.add(url);
            }
        }
        if (changed) {
            product.setImages(newUrls);
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
