package com.example.demo.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "combinations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Combination {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id_db; // database id

    private String id; // frontend id
    private String name;
    private Integer price;
    private String erpCode;
    private Integer stock;
}
