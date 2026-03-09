package com.example.demo.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    @JsonIgnore
    private Product product;

    private String id; // frontend id
    private String name;
    private Integer price;
    private String erpCode;
    private Integer stock;
    private Integer sortOrder;
}
