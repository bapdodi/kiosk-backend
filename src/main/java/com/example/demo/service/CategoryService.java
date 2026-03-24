package com.example.demo.service;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.entity.Category;
import com.example.demo.repository.CategoryRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryRepository categoryRepository;

    public List<Category> getAllCategories() {
        return categoryRepository.findAllByOrderBySortOrderAsc();
    }

    public List<Category> getCategoriesByLevel(String level) {
        return categoryRepository.findByLevel(level);
    }

    @Transactional
    public Category saveCategory(Category category) {
        return categoryRepository.save(category);
    }

    @Transactional
    public Category updateCategory(String id, Category categoryDetails) {
        Category category = categoryRepository.findById(id)
                .orElseGet(() -> {
                    categoryDetails.setId(id);
                    return categoryDetails;
                });

        category.setName(categoryDetails.getName());
        category.setParentId(categoryDetails.getParentId());
        category.setLevel(categoryDetails.getLevel());
        if (categoryDetails.getSortOrder() != null) {
            category.setSortOrder(categoryDetails.getSortOrder());
        }

        return categoryRepository.save(category);
    }

    @Transactional
    public void updateCategoryOrders(List<Category> categories) {
        Map<String, String> orderMap = categories.stream()
                .collect(java.util.stream.Collectors.toMap(Category::getId, Category::getSortOrder));
        List<Category> existing = categoryRepository.findAllById(orderMap.keySet());
        existing.forEach(c -> {
            String newOrder = orderMap.get(c.getId());
            if (newOrder != null) c.setSortOrder(newOrder);
        });
        categoryRepository.saveAll(existing);
    }

    @Transactional
    public void deleteCategory(String id) {
        categoryRepository.deleteById(id);
    }
}
