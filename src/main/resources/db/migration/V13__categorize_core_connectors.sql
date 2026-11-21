-- ERP 동기화로 미분류(uncategorized)에 머물러 키오스크 화면(공구류 탭)에 노출되지 않던
-- 코아 연결대 및 관련 코아 부자재 상품들을 올바른 카테고리(실리콘/설비부자재/공구 > 공구류)로 매핑하고,
-- 손님이 키오스크에서 '코아', '코아연결대', '코아대' 등으로 검색할 수 있도록 검색 해시태그를 등록한다.

DO $$
BEGIN
  -- 1. '연결대' (product_id: 9259) 카테고리 설정: 실리콘/설비부자재/공구 > 공구류
  IF EXISTS (SELECT 1 FROM products WHERE id = 9259) THEN
    UPDATE product_categories 
    SET main_category = 'cat_1776999431614', sub_category = 'sub_1788856658484' 
    WHERE product_id = 9259;

    -- 2. '연결대' 검색 해시태그 등록 (코아, 코아연결대, 코아대, SDS 등)
    DELETE FROM product_hashtags WHERE product_id = 9259;
    INSERT INTO product_hashtags (product_id, hashtags) VALUES
      (9259, '코아'),
      (9259, '코아연결대'),
      (9259, '코아대'),
      (9259, 'SDS'),
      (9259, 'SDS코아연결대');
  END IF;
END $$;

-- 3. 관련 코아/아답타 미분류 상품들도 공구류로 함께 매핑
-- (9289: 코아비트(융착)이화, 9253: 기리(융착코어비트), 9300: 힐티용 아답타, 9331: 힐티용(아답타), 9342: 힐티용 척)
UPDATE product_categories 
SET main_category = 'cat_1776999431614', sub_category = 'sub_1788856658484' 
WHERE product_id IN (9289, 9253, 9300, 9331, 9342) AND main_category = 'uncategorized';
