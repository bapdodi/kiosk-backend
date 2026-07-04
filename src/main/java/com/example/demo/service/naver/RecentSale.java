package com.example.demo.service.naver;

/**
 * 네이버에서 최근 발생한 판매(상품주문) 1건. 대시보드 "최근 판매" 표시에 사용한다.
 * kioskProductId/kioskProductName 은 채널상품번호로 키오스크 상품과 매칭된 경우에만 채워진다.
 */
public record RecentSale(
        String productOrderId,
        String orderId,
        Long channelProductNo,
        Long originProductNo,
        String naverProductName,
        int quantity,
        long totalPaymentAmount,
        String orderStatus,
        String orderDate,
        Long kioskProductId,
        String kioskProductName) {

    /** 키오스크 상품 매칭 결과를 덧붙인 새 인스턴스. */
    public RecentSale withKioskProduct(Long kioskProductId, String kioskProductName) {
        return new RecentSale(productOrderId, orderId, channelProductNo, originProductNo,
                naverProductName, quantity, totalPaymentAmount, orderStatus, orderDate,
                kioskProductId, kioskProductName);
    }
}
