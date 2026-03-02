package com.example.demo.service;

import java.util.List;

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
        for (Category input : categories) {
            categoryRepository.findById(input.getId()).ifPresent(c -> {
                c.setSortOrder(input.getSortOrder());
                categoryRepository.save(c);
            });
        }
    }

    @Transactional
    public void deleteCategory(String id) {
        categoryRepository.deleteById(id);
    }
}
