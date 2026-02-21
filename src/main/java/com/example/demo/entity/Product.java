package com.example.demo.entity;

import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "products")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true)
    private String erpCode;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    private String mainCategory;
    private String subCategory;
    private String detailCategory;

    @Column(nullable = false)
    private Integer price;

    @ElementCollection
    @CollectionTable(name = "product_hashtags", joinColumns = @JoinColumn(name = "product_id"))
    private List<String> hashtags;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "product_images", joinColumns = @JoinColumn(name = "product_id"))
    @Column(name = "image_url")
    @Builder.Default
    private List<String> images = new ArrayList<>();

    private Boolean isComplexOptions;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "product_id")
    private List<OptionGroup> optionGroups;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "product_id")
    private List<Combination> combinations;
}

@Entity
@Table(name = "option_groups")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
class OptionGroup {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    @ElementCollection
    private List<String> values;
}

@Entity
@Table(name = "combinations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
class Combination {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id_db; // database id

    private String id; // frontend id
    private String name;
    private Integer price;
}
