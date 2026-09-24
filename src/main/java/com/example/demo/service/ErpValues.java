package com.example.demo.service;

/** ERP(MSSQL) 조회 결과 값 변환. 숫자 컬럼이 decimal·문자열·NULL 로 섞여 온다. */
final class ErpValues {

    private ErpValues() {
    }

    /** 숫자로 못 읽으면 0. ERP 는 빈 단가·재고를 NULL 이나 빈 문자열로 둔다. */
    static Integer toInteger(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return 0;
        }
    }
}
