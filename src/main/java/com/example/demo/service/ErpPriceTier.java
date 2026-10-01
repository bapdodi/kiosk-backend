package com.example.demo.service;

/**
 * 키오스크 판매 단가를 고른다.
 * 거래처 등급(ERP GURAE.DANGA)과 상관없이 모든 손님에게 A단가(ERP OUTA)를 받는다.
 * 손님마다 가격이 달랐던 시절의 규칙을 걷어내고 한 가지로 맞춘 것이다.
 *
 * 주문 접수 때 금액을 매기는 {@link OrderService}, ERP 에 전표를 넣는 {@link ErpOrderSender},
 * 손님 화면에 내려가는 가격({@link PublicProductJson})이 모두 이 규칙을 쓴다.
 * 세 곳의 차이는 단가 출처뿐이다: 주문 접수·화면은 로컬 상품 사본, ERP 전송은 ERP 현재 단가.
 */
public final class ErpPriceTier {

    private ErpPriceTier() {
    }

    /** A단가. 비었거나 0 이하면 소비자가(C)로 대신한다(0원 주문을 막기 위한 안전장치). */
    public static Integer price(Integer priceA, Integer priceC) {
        return priceA != null && priceA > 0 ? priceA : priceC;
    }
}
