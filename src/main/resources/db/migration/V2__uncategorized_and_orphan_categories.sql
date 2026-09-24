-- 분류가 꼬인 상품·카테고리 정리.
-- ERP 분류 카테고리(erp-N-0-0) 자동 생성을 끈 뒤에도 동기화가 새 상품을 그 분류에 넣어,
-- 존재하지 않는 대분류에 매달린 상품이 키오스크 카테고리 화면에서 사라졌다.

-- 1. 새 ERP 상품과 분류가 사라진 상품이 놓일 "미분류" 대분류. 대분류 맨 뒤에 둔다.
INSERT INTO categories (id, name, parent_id, level, sort_order)
SELECT 'uncategorized', '미분류', NULL, 'main',
       COALESCE((SELECT MAX(sort_order) + 1 FROM categories WHERE level = 'main'), 0)
WHERE NOT EXISTS (SELECT 1 FROM categories WHERE id = 'uncategorized');

-- 2. 판매 상품이 아닌 ERP 비용·회계 품목은 휴지통으로 보낸다(ErpSyncService.NON_PRODUCT_ITEM_NAMES 와 같은 목록).
UPDATE products
SET deleted_at = now(), deleted_by = 'system:non-product-erp-item'
WHERE deleted_at IS NULL
  AND name IN ('부가세', '운반비', '용달비', '택배', '택배비', '화물비', '퀵비용',
               '할인액', '현금할인', '차액', '공과잡비', '선수금', '선입금', '증권',
               '배관작업', '전기작업', '절단비용', '배관및펌프철거', '화장실 배관누수공사', '압착기계 임대');

-- 3. 부모가 없는 카테고리와 그 하위 전체를 지운다. 대분류를 지워도 하위가 남던 흔적이다.
WITH RECURSIVE orphan AS (
    SELECT c.id FROM categories c
    WHERE c.parent_id IS NOT NULL AND c.parent_id <> ''
      AND NOT EXISTS (SELECT 1 FROM categories p WHERE p.id = c.parent_id)
    UNION
    SELECT c.id FROM categories c JOIN orphan o ON c.parent_id = o.id
)
DELETE FROM categories WHERE id IN (SELECT id FROM orphan);

-- 4. 없는 대분류를 가리키는 상품 분류를 지우고, 분류가 하나도 안 남은 상품은 미분류로 옮긴다.
--    휴지통 상품도 복원했을 때 사라지지 않도록 같이 옮긴다.
DELETE FROM product_categories pc
WHERE NOT EXISTS (SELECT 1 FROM categories c WHERE c.id = pc.main_category);

UPDATE product_categories pc SET sub_category = NULL
WHERE pc.sub_category IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM categories c WHERE c.id = pc.sub_category);

INSERT INTO product_categories (product_id, main_category, sub_category)
SELECT p.id, 'uncategorized', NULL FROM products p
WHERE NOT EXISTS (SELECT 1 FROM product_categories pc WHERE pc.product_id = p.id);
