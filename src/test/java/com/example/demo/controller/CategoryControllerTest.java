package com.example.demo.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import com.example.demo.config.SecurityConfig;
import com.example.demo.entity.Category;
import com.example.demo.repository.CategoryRepository;
import com.example.demo.service.CategoryService;
import com.example.demo.service.CustomUserDetailsService;
import com.fasterxml.jackson.databind.ObjectMapper;

@WebMvcTest(CategoryController.class)
// @WebMvcTest 는 @Service 를 스캔하지 않는다. CategoryService 를 함께 올려
// 실제 정렬/순서 보존 로직이 mock 리포지토리 위에서 그대로 검증되게 한다.
@Import({ SecurityConfig.class, CategoryService.class })
public class CategoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CategoryRepository categoryRepository;

    @MockBean
    private CustomUserDetailsService customUserDetailsService; // SecurityConfig에 필요한 Bean Mocking

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("전체 카테고리 조회")
    @WithMockUser
    void getAllCategories() throws Exception {
        // given
        Category category1 = Category.builder().id("1").name("한식").build();
        Category category2 = Category.builder().id("2").name("양식").build();
        List<Category> categories = Arrays.asList(category1, category2);

        given(categoryRepository.findAllByOrderBySortOrderAscIdAsc()).willReturn(categories);

        // when & then
        mockMvc.perform(get("/api/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size()").value(2))
                .andExpect(jsonPath("$[0].name").value("한식"))
                .andExpect(jsonPath("$[1].name").value("양식"));
    }

    @Test
    @DisplayName("레벨별 카테고리 조회")
    @WithMockUser
    void getCategoriesByLevel() throws Exception {
        // given
        String level = "main";
        Category category = Category.builder().id("1").name("메인").level("main").build();
        List<Category> categories = Arrays.asList(category);

        given(categoryRepository.findByLevelOrderBySortOrderAscIdAsc(level)).willReturn(categories);

        // when & then
        mockMvc.perform(get("/api/categories/level/{level}", level))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size()").value(1))
                .andExpect(jsonPath("$[0].level").value("main"));
    }

    @Test
    @DisplayName("카테고리 생성 - 관리자 권한 필요")
    @WithMockUser(roles = "ADMIN")
    void createCategory() throws Exception {
        // given
        Category category = Category.builder().id("3").name("중식").build();
        given(categoryRepository.save(any(Category.class))).willReturn(category);

        // when & then
        mockMvc.perform(post("/api/categories/admin")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(category))
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                        .csrf())) // CSRF 토큰 추가
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("중식"));
    }

    @Test
    @DisplayName("카테고리 생성 - 일반 사용자 접근 불가")
    @WithMockUser(roles = "USER")
    void createCategory_Forbidden() throws Exception {
        // given
        Category category = Category.builder().id("3").name("중식").build();

        // when & then
        mockMvc.perform(post("/api/categories/admin")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(category))
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                        .csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("카테고리 삭제 - 관리자 권한 필요")
    @WithMockUser(roles = "ADMIN")
    void deleteCategory() throws Exception {
        // given
        String categoryId = "1";

        // when & then
        mockMvc.perform(delete("/api/categories/admin/{id}", categoryId)
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                        .csrf()))
                .andExpect(status().isOk());

        verify(categoryRepository).deleteById(categoryId);
    }

    @Test
    @DisplayName("이름만 수정하면 sortOrder 가 유지된다")
    @WithMockUser(roles = "ADMIN")
    void updateCategory_keepsSortOrder() throws Exception {
        // given: 서버에는 sortOrder=5 로 저장된 카테고리가 있고
        Category stored = Category.builder().id("1").name("한식").level("main").sortOrder(5).build();
        given(categoryRepository.findById("1")).willReturn(java.util.Optional.of(stored));
        given(categoryRepository.save(any(Category.class)))
                .willAnswer(inv -> inv.getArgument(0));

        // when: 클라이언트가 sortOrder 없이 이름만 바꿔 보내면
        mockMvc.perform(put("/api/categories/admin/{id}", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"1\",\"name\":\"한식당\",\"level\":\"main\",\"parentId\":null}")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                        .csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("한식당"))
                // then: 순서는 0 으로 초기화되지 않고 그대로 5 여야 한다
                .andExpect(jsonPath("$.sortOrder").value(5));
    }
}
