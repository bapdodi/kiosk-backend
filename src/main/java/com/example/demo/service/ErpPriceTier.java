package com.example.demo.service;

/**
 * 거래처 단가 등급(ERP GURAE.DANGA)으로 품목의 A/B/C 단가 중 하나를 고른다.
 * 주문 접수 때 금액을 매기는 {@link OrderService} 와 ERP 에 전표를 넣는 {@link ErpOrderSender} 가 같이 쓴다.
 *
 * 등급을 못 고르면 null 을 돌려주고, 대신 쓸 가격은 호출하는 쪽이 정한다. 지금은 두 쪽이 다르다:
 * 주문 접수는 등급이 없으면(null) 소비자가(C)로, ERP 전송은 등급이 없으면 2(A)로 보고 못 고르면
 * 주문 때 매긴 가격을 그대로 보낸다. 그래서 DANGA 가 빈 거래처는 주문 화면과 ERP 청구가가 다르다.
 */
public final class ErpPriceTier {

    private ErpPriceTier() {
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
