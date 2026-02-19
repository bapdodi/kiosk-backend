package com.example.demo.controller;

import java.util.List;

import org.springframework.web.bind.annotation.*;

import lombok.RequiredArgsConstructor;
import com.example.demo.entity.Category;
import com.example.demo.repository.CategoryRepository;

@RestController
@RequestMapping("/api/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryRepository categoryRepository;

    @GetMapping
    public List<Category> getAllCategories() {
        return categoryRepository.findAll();
    }

    @GetMapping("/level/{level}")
    public List<Category> getCategoriesByLevel(@PathVariable String level) {
        return categoryRepository.findByLevel(level);
    }

    @PostMapping("/admin")
    public Category createCategory(@RequestBody Category category) {
        return categoryRepository.save(category);
    }

    @DeleteMapping("/admin/{id}")
    public void deleteCategory(@PathVariable String id) {
        categoryRepository.deleteById(id);
    }
}