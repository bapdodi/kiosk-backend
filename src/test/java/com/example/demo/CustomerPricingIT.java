package com.example.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.demo.entity.Customer;
import com.example.demo.entity.Order;
import com.example.demo.entity.OrderItem;
import com.example.demo.entity.Product;
import com.example.demo.repository.CustomerRepository;
import com.example.demo.repository.ProductRepository;
import com.example.demo.service.ErpSyncService;
import com.example.demo.service.OrderService;

/** ERP(MSSQL) 와 상품 DB 가 둘 다 떠 있어야 하므로 KIOSK_DB_IT 를 설정한 로컬에서만 돈다. */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "KIOSK_DB_IT", matches = "1")
class CustomerPricingIT {

    @Autowired
    ErpSyncService erpSyncService;

    @Autowired
    CustomerRepository customerRepository;

    @Autowired
    ProductRepository productRepository;

    @Autowired
    OrderService orderService;

    /** 검증용으로 심은 가짜 거래처는 남기지 않는다. 실제 ERP 사본과 섞이면 헷갈린다. */
    @AfterEach
    void 검증용_거래처_정리() {
        customerRepository.deleteAllById(List.of("IT-A", "IT-B", "IT-PURCHASE"));
    }

    @Test
    void 거래처를_동기화하면_단가등급이_함께_복사된다() {
        int synced = erpSyncService.syncCustomers();

        assertThat(synced).isPositive();
        // DANGA 는 거래처마다 다르다. 등급이 하나라도 안 넘어왔다면 매핑이 깨진 것이다.
        assertThat(customerRepository.findAll())
                .anyMatch(c -> c.getDanga() != null && c.getDanga() == 2)
                .anyMatch(c -> c.getDanga() != null && c.getDanga() == 3);
    }

    @Test
    void 주문_생성_시점부터_거래처_단가로_계산된다() {
        // A단가와 소비자가가 실제로 다른 상품이라야 등급 선택 여부를 구분할 수 있다.
        Product product = productRepository.findAll().stream()
                .filter(p -> p.getErpCode() != null && !p.getErpCode().isBlank())
                .filter(p -> p.getPriceA() != null && p.getPriceA() > 0)
                .filter(p -> p.getPriceB() != null && p.getPriceB() > 0)
                .filter(p -> !p.getPriceA().equals(p.getPriceC()) && !p.getPriceB().equals(p.getPriceC()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("A/B/C 단가가 서로 다른 상품이 없어 검증할 수 없다"));

        String erpCode = product.getErpCode();
        customerRepository.save(customer("IT-A", 2));
        customerRepository.save(customer("IT-B", 3));
        customerRepository.save(customer("IT-PURCHASE", 1));

        assertThat(unitPriceFor("IT-A", erpCode)).isEqualTo(product.getPriceA());
        assertThat(unitPriceFor("IT-B", erpCode)).isEqualTo(product.getPriceB());
        // 단가표가 없는 거래처(매입처)와 사본에 없는 거래처는 소비자가로 떨어진다.
        assertThat(unitPriceFor("IT-PURCHASE", erpCode)).isEqualTo(product.getPriceC());
        assertThat(unitPriceFor("IT-UNKNOWN", erpCode)).isEqualTo(product.getPriceC());
    }

    private Customer customer(String code, int danga) {
        return Customer.builder().erpCode(code).name(code).kind("1").danga(danga).build();
    }

    /** 주문을 한 건 만들어 서버가 확정한 단가를 읽는다. 수량 1 이라 총액이 곧 단가다. */
    private int unitPriceFor(String erpCustomerCode, String erpCode) {
        OrderItem item = new OrderItem();
        item.setErpCode(erpCode);
        item.setName("단가검증");
        item.setQuantity(1);

        Order order = new Order();
        order.setCustomerName(erpCustomerCode);
        order.setErpCustomerCode(erpCustomerCode);
        order.setItems(List.of(item));
        order.setStatus("pending");

        Order saved = orderService.createOrder(order, null);
        try {
            return saved.getItems().get(0).getFinalPrice();
        } finally {
            orderService.deleteOrder(saved.getId());
        }
    }
}
