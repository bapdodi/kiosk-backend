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

    /**
     * 카테고리를 하위 분류까지 함께 지운다. 예전엔 대분류만 지워져 하위 분류와 상품 분류가
     * 없는 카테고리를 가리킨 채 남았다(키오스크 화면에서 상품이 사라짐). 이제 DB FK 가 그걸 막으므로,
     * 지울 범위에 상품이 하나라도 걸려 있으면 이유를 알려 주고 거절한다.
     */
    @Transactional
    public void deleteCategory(String id) {
        if (Category.UNCATEGORIZED_ID.equals(id)) {
            throw new IllegalStateException("미분류는 ERP 에서 새로 들어온 상품이 놓이는 분류라 삭제할 수 없습니다.");
        }
        List<String> subtree = subtreeIds(id);
        long live = categoryRepository.countProductsUsing(subtree, false);
        long trashed = categoryRepository.countProductsUsing(subtree, true);
        if (live + trashed > 0) {
            String where = subtree.size() > 1 ? "이 분류와 하위 분류에" : "이 분류에";
            String trashNote = trashed > 0 ? " (휴지통 " + trashed + "개 포함)" : "";
            throw new IllegalStateException(where + " 상품 " + (live + trashed) + "개가 연결돼 있습니다" + trashNote
                    + ". 상품을 다른 분류로 옮긴 뒤 삭제하세요.");
        }
        // 한 문장으로 지워야 부모-자식 FK 가 문장 끝에서 한 번에 검사된다.
        categoryRepository.deleteAllByIdInBatch(subtree);
    }

    /** id 자신과 모든 하위 카테고리 id. */
    private List<String> subtreeIds(String id) {
        List<String> ids = new java.util.ArrayList<>();
        java.util.Deque<String> queue = new java.util.ArrayDeque<>(List.of(id));
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (ids.contains(current)) continue;
            ids.add(current);
            categoryRepository.findByParentIdOrderBySortOrderAscIdAsc(current)
                    .forEach(child -> queue.add(child.getId()));
        }
        return ids;
    }
}
