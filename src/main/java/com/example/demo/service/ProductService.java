package com.example.demo.service;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

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
        renameProductImages(saved);
        return productRepository.save(saved);
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

                    Product saved = productRepository.save(product);
                    renameProductImages(saved);
                    return productRepository.save(saved);
                });
    }

    private void renameProductImages(Product product) {
        if (product.getImages() == null || product.getImages().isEmpty())
            return;

        java.util.List<String> newUrls = new java.util.ArrayList<>();

        for (int i = 0; i < product.getImages().size(); i++) {
            String url = product.getImages().get(i);
            if (url != null && url.contains("/uploads/")) {
                try {
                    String encodedFileName = url.substring(url.lastIndexOf("/") + 1);
                    // Decode the filename from URL
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
                } catch (Exception e) {
                    newUrls.add(url);
                }
            } else {
                newUrls.add(url);
            }
        }
        product.setImages(newUrls);
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
        for (Product input : products) {
            productRepository.findById(input.getId()).ifPresent(p -> {
                p.setSortOrder(input.getSortOrder());
                productRepository.save(p);
            });
        }
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
