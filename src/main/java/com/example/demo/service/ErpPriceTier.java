package com.example.demo.service;

/**
 * 거래처 단가 등급(ERP GURAE.DANGA)으로 품목 단가를 고른다.
 * 주문 접수 때 금액을 매기는 {@link OrderService} 와 ERP 에 전표를 넣는 {@link ErpOrderSender} 가
 * 같은 규칙을 쓴다. 두 쪽의 차이는 단가 출처뿐이다: 주문 접수는 로컬 상품 사본, ERP 전송은 ERP 현재 단가.
 */
public final class ErpPriceTier {

    private ErpPriceTier() {
    }

    /**
     * 등급에 맞는 단가. 등급이 없거나(거래처 미선택·DANGA 비어 있음) 못 고르면 소비자가(C).
     * DANGA=1(매입 거래처)은 주문 화면 거래처 목록에서 걸러지므로 정상 경로로는 오지 않는다.
     */
    public static Integer price(Integer danga, Integer priceA, Integer priceB, Integer priceC) {
        Integer tierPrice = select(danga, priceA, priceB, priceC);
        return tierPrice != null ? tierPrice : priceC;
    }

    /** DANGA 2=A, 3=B, 4=C 단가. 등급이 그 밖이거나 해당 단가가 0 이하면 null. */
    public static Integer select(Integer danga, Integer priceA, Integer priceB, Integer priceC) {
        if (danga == null) {
            return null;
        }
        if (danga == 2 && priceA != null && priceA > 0) {
            return priceA;
        }
        if (danga == 3 && priceB != null && priceB > 0) {
            return priceB;
        }
        if (danga == 4 && priceC != null && priceC > 0) {
            return priceC;
        }
        return null;
    }
}
