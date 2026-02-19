package com.example.demo.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import com.example.demo.entity.Category;

public interface CategoryRepository extends JpaRepository<Category, String> {
    List<Category> findByLevel(String level);

    List<Category> findByParentId(String parentId);
}