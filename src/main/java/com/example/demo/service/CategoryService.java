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
        // getAllCategories 와 동일한 DB 정렬을 쓴다. 인메모리 nullsFirst 로 정렬하면
        // Postgres 의 ASC(NULLS LAST) 와 어긋나 두 API 가 서로 다른 순서를 돌려준다.
        return categoryRepository.findByLevelOrderBySortOrderAscIdAsc(level);
    }

    public List<Category> getCategoriesByParent(String parentId) {
        return categoryRepository.findByParentIdOrderBySortOrderAscIdAsc(parentId);
    }

    @Transactional
    public Category saveCategory(Category category) {
        // 신규 카테고리의 기본 sortOrder(0)가 기존 항목과 충돌하지 않도록
        // 같은 레벨/부모 그룹의 마지막 순서 뒤에 배치한다.
        if (!categoryRepository.existsById(category.getId())
                && (category.getSortOrder() == null || category.getSortOrder() == 0)) {
            // 동시 생성 시 두 요청이 같은 max 를 읽어 같은 순서를 배정받는 것을 막는다.
            categoryRepository.lockSortOrderAssignment();
            category.setSortOrder(nextOrderIn(category.getLevel(), category.getParentId()));
        }
        return categoryRepository.save(category);
    }

    /** 같은 (level, parentId) 그룹의 max(sortOrder) + 1. 그룹이 비어 있으면 0. */
    private int nextOrderIn(String level, String parentId) {
        return categoryRepository.findAll().stream()
                .filter(c -> java.util.Objects.equals(c.getLevel(), level))
                .filter(c -> java.util.Objects.equals(c.getParentId(), parentId))
                .map(Category::getSortOrder)
                .filter(java.util.Objects::nonNull)
                .mapToInt(Integer::intValue)
                .max()
                .orElse(-1) + 1;
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
        // 요청에 sortOrder 가 없으면(=null) 기존 순서를 유지한다.
        // 이름만 수정할 때 순서가 초기화되던 원인이라, 여기서 절대 덮어쓰지 않는다.
        if (categoryDetails.getSortOrder() != null) {
            category.setSortOrder(categoryDetails.getSortOrder());
        }
        // 신규 생성으로 흘러왔거나 과거 데이터라 순서가 비어 있으면 그룹 맨 뒤에 붙인다.
        if (category.getSortOrder() == null) {
            category.setSortOrder(nextOrderIn(category.getLevel(), category.getParentId()));
        }

        return categoryRepository.save(category);
    }

    @Transactional
    public void updateCategoryOrders(List<Category> categories) {
        // Collectors.toMap 은 값이 null 이면 NPE 를 던진다 → 순서 저장이 통째로 500 이 된다.
        Map<String, Integer> orderMap = new java.util.HashMap<>();
        categories.stream()
                .filter(c -> c.getId() != null && c.getSortOrder() != null)
                .forEach(c -> orderMap.put(c.getId(), c.getSortOrder()));
        if (orderMap.isEmpty()) {
            return;
        }
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
