package com.example.demo.service;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 키오스크끼리(주문·입고·발주) 전표번호 채번을 직렬화하는 SQL Server 앱 잠금.
 * IL 테이블 자체는 잠그지 않아 경영박사를 막지 않는다. 호출한 ERP 트랜잭션이 끝나면 풀린다.
 */
final class ErpVoucherLock {

    private ErpVoucherLock() {
    }

    static void acquire(JdbcTemplate erp, String ilTable) {
        Integer result = erp.queryForObject(
                "SET NOCOUNT ON; DECLARE @r int;"
                        + " EXEC @r = sp_getapplock @Resource = ?, @LockMode = 'Exclusive',"
                        + " @LockOwner = 'Transaction', @LockTimeout = 30000;"
                        + " SELECT @r",
                Integer.class, "kiosk-dno-" + ilTable);
        if (result == null || result < 0) {
            throw new IllegalStateException("ERP 전표번호 잠금을 얻지 못했습니다(" + result + "). 잠시 후 다시 시도합니다.");
        }
    }
}
