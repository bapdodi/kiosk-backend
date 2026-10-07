package com.example.demo.dto;

import java.util.List;
import java.util.Set;

import com.example.demo.entity.CategoryRef;
import com.example.demo.entity.OptionImage;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 키오스크 표시 정보만 수정한다. 누락된 항목은 기존 값을 유지한다. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductUpdateRequest(
        Long id,
        String name,
        String description,
        Set<CategoryRef> categories,
        List<String> hashtags,
        List<String> images,
        List<OptionImage> optionImages,
        String sortOrder,
        List<CombinationDisplayUpdate> combinations) {

    /** 규격 내용은 ERP가 관리하며, 키오스크에서는 순서·숨김·표시 이름만 바꿀 수 있다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CombinationDisplayUpdate(Long id_db, Boolean deleted, Integer sortOrder, String kioskName) {}
}
