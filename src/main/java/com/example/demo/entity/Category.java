package com.example.demo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "categories")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Category {

    @Id
    private String id; // string id like 'pipes', 'pipe_steel'

    private String name;

    private String parentId;

    private String level; // main, sub, detail

    @Builder.Default
    @Column(columnDefinition = "int default 0")
    private Integer sortOrder = 0;
}
