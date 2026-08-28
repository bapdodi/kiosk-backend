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

    // 초기화자(= 0)를 두지 않는다. 두면 Jackson 이 @NoArgsConstructor 로 만든 객체의
    // sortOrder 가 0 이 되어, 요청 JSON 에 필드가 없는지(수정 안 함) 0 을 보낸 건지
    // 구분할 수 없다 → 이름만 고쳐도 순서가 맨 앞으로 초기화된다.
    // 신규 행의 기본값은 CategoryService.saveCategory 가 그룹 max+1 로 채운다.
    @Column(columnDefinition = "int default 0")
    private Integer sortOrder;
}
