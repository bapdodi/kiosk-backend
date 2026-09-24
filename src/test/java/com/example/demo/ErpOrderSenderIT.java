package com.example.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.demo.entity.Order;
import com.example.demo.entity.OrderItem;
import com.example.demo.service.ErpOrderSender;

/** 일회용 MSSQL 복사본에서만 실행한다. 전표 잠금과 실청구가는 실제 SQL Server로 확인한다. */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "KIOSK_ERP_WRITE_IT", matches = "1")
class ErpOrderSenderIT {

    private static final long FIRST_ID = 900000001L;
    private static final long SECOND_ID = 900000002L;
    private static final String DATE = "26.09.24";

    @Autowired ErpOrderSender sender;
    @Autowired @Qualifier("erpJdbcTemplate") JdbcTemplate erp;

    @AfterEach
    void cleanUp() {
        for (long id : List.of(FIRST_ID, SECOND_ID)) {
            String tag = "KIOSK-" + id;
            erp.update("DELETE FROM IL26 WHERE dDATE = ? AND BIGO2 = ?", DATE, tag);
            erp.update("DELETE FROM SUJU WHERE BALJUNO = ?", tag);
            erp.update("DELETE FROM KIOSK_ORDER_RECEIPT WHERE ORDER_ID = ?", id);
        }
    }

    @Test
    void 동시_전송은_다른_전표번호를_받고_소비자가로_청구된다() {
        Order first = order(FIRST_ID);
        Order second = order(SECOND_ID);
        CompletableFuture<Void> a = CompletableFuture.runAsync(() -> sender.sendOrderToErp(first));
        CompletableFuture<Void> b = CompletableFuture.runAsync(() -> sender.sendOrderToErp(second));
        CompletableFuture.allOf(a, b).join();

        List<Integer> numbers = erp.queryForList(
                "SELECT DISTINCT dNO FROM IL26 WHERE dDATE = ? AND BIGO2 IN (?, ?)", Integer.class,
                DATE, "KIOSK-" + FIRST_ID, "KIOSK-" + SECOND_ID);
        assertThat(numbers).hasSize(2).doesNotHaveDuplicates();
        assertThat(first.getItems().get(0).getChargedPrice()).isEqualTo(4000);
        assertThat(second.getItems().get(0).getChargedPrice()).isEqualTo(4000);
        assertThat(first.getTotalAmount()).isEqualTo(4000);
    }

    private static Order order(long id) {
        OrderItem item = new OrderItem();
        item.setErpCode("103");
        item.setName("전표검증");
        item.setQuantity(1);
        item.setFinalPrice(4000);
        Order order = new Order();
        order.setId(id);
        order.setTimestamp(LocalDateTime.of(2026, 9, 24, 12, 0));
        order.setItems(List.of(item));
        return order;
    }
}
