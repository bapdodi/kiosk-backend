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
        return categoryRepository.findAllByOrderBySortOrderAscIdAsc();
    }

    public List<Category> getCategoriesByLevel(String level) {
        return categoryRepository.findByLevel(level).stream()
                .sorted(java.util.Comparator
                        .comparing(Category::getSortOrder, java.util.Comparator.nullsFirst(Integer::compareTo))
                        .thenComparing(Category::getId, java.util.Comparator.nullsFirst(String::compareTo)))
                .toList();
    }

    @Transactional
    public Category saveCategory(Category category) {
        // 신규 카테고리의 기본 sortOrder(0)가 기존 항목과 충돌하지 않도록
        // 같은 레벨/부모 그룹의 마지막 순서 뒤에 배치한다.
        if (!categoryRepository.existsById(category.getId())
                && (category.getSortOrder() == null || category.getSortOrder() == 0)) {
            int nextOrder = categoryRepository.findAll().stream()
                    .filter(c -> java.util.Objects.equals(c.getLevel(), category.getLevel()))
                    .filter(c -> java.util.Objects.equals(c.getParentId(), category.getParentId()))
                    .map(Category::getSortOrder)
                    .filter(java.util.Objects::nonNull)
                    .mapToInt(Integer::intValue)
                    .max()
                    .orElse(-1) + 1;
            category.setSortOrder(nextOrder);
        }
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
        Map<String, Integer> orderMap = categories.stream()
                .collect(java.util.stream.Collectors.toMap(Category::getId, Category::getSortOrder));
        List<Category> existing = categoryRepository.findAllById(orderMap.keySet());
        existing.forEach(c -> {
            Integer newOrder = orderMap.get(c.getId());
            if (newOrder != null) c.setSortOrder(newOrder);
        });
        categoryRepository.saveAll(existing);
    }

    @Transactional
    public void deleteCategory(String id) {
        categoryRepository.deleteById(id);
    }
}
