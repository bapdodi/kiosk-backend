package com.example.demo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 옵션값 단위로 연결되는 상품 사진 한 장.
 * (groupName, optionValue) 로 그룹핑되며, 같은 옵션값에 여러 장을 둘 수 있어 평면 행으로 저장한다.
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OptionImage {

    private String groupName;

    private String optionValue;

    @Column(columnDefinition = "TEXT")
    private String imageUrl;
}
