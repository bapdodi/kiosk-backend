-- 상품 분류와 카테고리 부모가 없는 카테고리를 가리키지 못하게 한다.
-- 예전엔 관리자가 대분류를 지우면 하위 분류와 상품 분류가 그대로 남아 키오스크 화면에서 상품이 사라졌다
-- (V2 에서 정리한 고아 239개/상품 260개). 삭제는 이제 CategoryService.deleteCategory 가 하위까지
-- 함께 지우고, 상품이 걸려 있으면 거절한다. 여기 FK 는 그 규칙을 우회하는 경로까지 막는 마지막 방어선이다.
-- 네이버 매핑(channel_category_mappings)은 '*'(전체 기본값) 같은 가상 값을 쓰므로 FK 를 걸지 않는다.

-- 빈 문자열은 "없음"으로 쓰였다. FK 는 '' 를 없는 카테고리로 보므로 NULL 로 맞춘다.
UPDATE categories SET parent_id = NULL WHERE parent_id = '';
UPDATE product_categories SET sub_category = NULL WHERE sub_category = '';

-- V2 이후에 새로 생긴 고아가 있으면 V2 와 같은 규칙으로 정리해야 FK 를 걸 수 있다.
-- (1) 부모가 없는 카테고리와 그 하위 전체 삭제 → (2) 없는 분류를 가리키는 상품 분류 정리.
WITH RECURSIVE orphan AS (
    SELECT c.id FROM categories c
    WHERE c.parent_id IS NOT NULL
      AND NOT EXISTS (SELECT 1 FROM categories p WHERE p.id = c.parent_id)
    UNION
    SELECT c.id FROM categories c JOIN orphan o ON c.parent_id = o.id
)
DELETE FROM categories WHERE id IN (SELECT id FROM orphan);

DELETE FROM product_categories pc
WHERE pc.main_category IS NULL
   OR NOT EXISTS (SELECT 1 FROM categories c WHERE c.id = pc.main_category);

UPDATE product_categories pc SET sub_category = NULL
WHERE pc.sub_category IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM categories c WHERE c.id = pc.sub_category);

INSERT INTO product_categories (product_id, main_category, sub_category)
SELECT p.id, 'uncategorized', NULL FROM products p
WHERE NOT EXISTS (SELECT 1 FROM product_categories pc WHERE pc.product_id = p.id);

ALTER TABLE product_categories ALTER COLUMN main_category SET NOT NULL;

ALTER TABLE product_categories
    ADD CONSTRAINT fk_product_categories_main FOREIGN KEY (main_category) REFERENCES categories (id),
    ADD CONSTRAINT fk_product_categories_sub FOREIGN KEY (sub_category) REFERENCES categories (id);

ALTER TABLE categories
    ADD CONSTRAINT fk_categories_parent FOREIGN KEY (parent_id) REFERENCES categories (id);

-- 삭제 전 참조 검사와 FK 검사가 전체 스캔을 하지 않도록.
CREATE INDEX IF NOT EXISTS idx_product_categories_main ON product_categories (main_category);
CREATE INDEX IF NOT EXISTS idx_product_categories_sub ON product_categories (sub_category);
CREATE INDEX IF NOT EXISTS idx_categories_parent ON categories (parent_id);
