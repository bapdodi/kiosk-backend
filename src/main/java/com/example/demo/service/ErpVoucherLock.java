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
                // autocommit=false 는 IMPLICIT_TRANSACTIONS 라 EXEC 만으로는 트랜잭션이 안 열려 -999 가 난다.
                // 테이블을 읽는 SELECT 하나로 먼저 열어 둔다(입고처럼 이게 첫 문장인 경우).
                "SET NOCOUNT ON; DECLARE @r int; SELECT TOP 0 @r = 0 FROM sys.objects;"
                        + " EXEC @r = sp_getapplock @Resource = ?, @LockMode = 'Exclusive',"
                        + " @LockOwner = 'Transaction', @LockTimeout = 30000;"
                        + " SELECT @r",
                Integer.class, "kiosk-dno-" + ilTable);
        if (result == null || result < 0) {
            throw new IllegalStateException("ERP 전표번호 잠금을 얻지 못했습니다(" + result + "). 잠시 후 다시 시도합니다.");
        }
    }
}
