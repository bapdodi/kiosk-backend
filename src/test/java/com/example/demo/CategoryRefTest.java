package com.example.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import com.example.demo.entity.Category;
import com.example.demo.entity.CategoryRef;
import com.fasterxml.jackson.databind.ObjectMapper;

class CategoryRefTest {

    @Test
    void 관리자_화면이_보낸_빈_중분류는_null_로_저장된다() throws Exception {
        CategoryRef ref = new ObjectMapper().readValue(
                "{\"mainCategory\":\"cat_1\",\"subCategory\":\"\"}", CategoryRef.class);

        assertEquals("cat_1", ref.getMainCategory());
        assertNull(ref.getSubCategory());
        assertEquals(new CategoryRef("cat_1", null), ref); // 같은 분류로 봐야 isCategoryModified 가 헛돌지 않는다
    }

    @Test
    void 대분류의_빈_부모는_null_로_저장된다() throws Exception {
        Category category = new ObjectMapper().readValue(
                "{\"id\":\"cat_1\",\"name\":\"a\",\"level\":\"main\",\"parentId\":\"\"}", Category.class);

        assertNull(category.getParentId());
    }
}
