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
        return productRepository.findAll();
    }

    public Optional<Product> getProductById(Long id) {
        return productRepository.findById(id);
    }

    @Transactional
    public Product createProduct(Product product) {
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
                            || !productDetails.getSubCategory().equals(product.getSubCategory())
                            || !productDetails.getDetailCategory().equals(product.getDetailCategory());

                    if (categoryChanged) {
                        product.setIsCategoryModified(true);
                    }

                    product.setMainCategory(productDetails.getMainCategory());
                    product.setSubCategory(productDetails.getSubCategory());
                    product.setDetailCategory(productDetails.getDetailCategory());
                    product.setPrice(productDetails.getPrice());
                    product.setHashtags(productDetails.getHashtags());
                    product.setImages(productDetails.getImages());
                    product.setIsComplexOptions(productDetails.getIsComplexOptions());

                    product.getOptionGroups().clear();
                    if (productDetails.getOptionGroups() != null) {
                        product.getOptionGroups().addAll(productDetails.getOptionGroups());
                    }

                    product.getCombinations().clear();
                    if (productDetails.getCombinations() != null) {
                        product.getCombinations().addAll(productDetails.getCombinations());
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
    public boolean deleteProduct(Long id) {
        return productRepository.findById(id)
                .map(product -> {
                    productRepository.delete(product);
                    return true;
                })
                .orElse(false);
    }
}
