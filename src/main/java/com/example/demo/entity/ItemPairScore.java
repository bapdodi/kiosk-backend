package com.example.demo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * "이 상품을 주문한 전표에 함께 담긴 상품" 한 쌍. ERP 거래이력(ILxx)에서 야간 배치로 집계한다.
 *
 * 거래처 구분 없이 전체 전표를 대상으로 하고, 방향별로 각각 한 행씩 저장한다(A→B, B→A).
 * 조회는 언제나 sourceErpCode 기준 한 방향이라, 양방향을 따로 넣어야 인덱스 한 번으로 끝난다.
 */
@Entity
@Table(name = "reco_item_pair", indexes = {
        @Index(name = "idx_reco_item_pair_source", columnList = "source_erp_code")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ItemPairScore {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_erp_code", nullable = false, length = 20)
    private String sourceErpCode;

    @Column(name = "target_erp_code", nullable = false, length = 20)
    private String targetErpCode;

    /** 두 품목이 같은 전표에 함께 등장한 횟수. 노이즈 컷(min-co-count)과 노출 근거로 쓴다. */
    @Column(name = "co_count", nullable = false)
    private Integer coCount;

    /**
     * 동시구매 강도(코사인 유사도) = co / sqrt(n_a * n_b).
     * 단순 동시구매 횟수로 정렬하면 어디에나 끼는 인기 품목만 올라와서, 정규화된 값을 쓴다.
     */
    @Column(nullable = false)
    private Double score;
}
