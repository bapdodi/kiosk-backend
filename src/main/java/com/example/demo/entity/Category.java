package com.example.demo.entity;

import jakarta.persistence.*;
import lombok.*;

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
}
