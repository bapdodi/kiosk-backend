package com.example.demo;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import java.util.concurrent.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.demo.entity.Customer;
import com.example.demo.entity.Order;
import com.example.demo.repository.CustomerRepository;
import com.example.demo.service.CustomerStampService;

/** 실 PostgreSQL의 UPSERT 동시성/롤백 검증. 별도 스키마만 만들고 항상 지운다. */
@EnabledIfEnvironmentVariable(named = "KIOSK_STAMP_DB_URL", matches = ".+")
class CustomerStampDatabaseIT {
    JdbcTemplate admin, jdbc;
    String schema;
    CustomerStampService service;
    TransactionTemplate transactions;

    @BeforeEach
    void setup() {
        String url = System.getenv("KIOSK_STAMP_DB_URL");
        var ds = new DriverManagerDataSource(url, "admin", "password");
        admin = new JdbcTemplate(ds);
        schema = "stamp_it_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var scoped = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, "admin", "password");
        jdbc = new JdbcTemplate(scoped);
        jdbc.execute("CREATE TABLE orders (id bigint PRIMARY KEY, status varchar(20))");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V7__customer_order_stamps.sql")).execute(scoped);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(scoped));
        var customers = mock(CustomerRepository.class);
        for (String code : List.of("A", "B")) {
            when(customers.findByErpCode(code)).thenReturn(Optional.of(Customer.builder().erpCode(code).name(code).build()));
        }
        service = new CustomerStampService(jdbc, customers);
    }

    @AfterEach
    void cleanup() { if (schema != null) admin.execute("DROP SCHEMA " + schema + " CASCADE"); }

    private Order award(String code) {
        return transactions.execute(status -> {
            var order = Order.builder().erpCustomerCode(code).build();
            service.award(order);
            return order;
        });
    }

    @Test
    void 고객별_독립적립과_다섯개_완성_다음판_첫도장() {
        for (int i = 1; i <= 5; i++) {
            Order order = award("A");
            assertEquals(i, order.getStampCount());
            assertEquals(i == 5, order.isStampRewardEarned());
        }
        assertEquals(1, award("B").getStampCount());
        assertEquals(1, award("A").getStampCount());
    }

    @Test
    void 주문실패로_트랜잭션이_롤백되면_도장도_취소된다() {
        assertThrows(IllegalStateException.class, () -> transactions.execute(status -> {
            service.award(Order.builder().erpCustomerCode("A").build());
            throw new IllegalStateException("order save failed");
        }));
        assertEquals(1, award("A").getStampCount());
    }

    @Test
    void 같은_고객의_동시주문도_적립을_잃지_않는다() throws Exception {
        var executor = Executors.newFixedThreadPool(5);
        try {
            var tasks = new ArrayList<Callable<Order>>();
            for (int i = 0; i < 10; i++) tasks.add(() -> award("A"));
            var results = executor.invokeAll(tasks);
            int rewards = 0;
            for (var result : results) if (result.get().isStampRewardEarned()) rewards++;
            assertEquals(2, rewards);
            assertEquals(10L, jdbc.queryForObject("SELECT total_stamps FROM customer_stamp_accounts WHERE customer_code='A'", Long.class));
        } finally { executor.shutdownNow(); }
    }
    @Test
    void 상품지급은_완성한_유효주문에만_한번_기록한다() {
        jdbc.update("INSERT INTO orders (id, status, stamp_reward_earned) VALUES (1, 'pending', true), (2, 'pending', false), (3, 'cancelled', true)");
        var orders = mock(com.example.demo.repository.OrderRepository.class);
        var orderService = new com.example.demo.service.OrderService(orders,
                mock(com.example.demo.repository.ProductRepository.class),
                mock(com.example.demo.repository.CombinationRepository.class), jdbc,
                mock(com.example.demo.repository.ErpOrderOutboxRepository.class),
                mock(org.springframework.context.ApplicationEventPublisher.class), service);
        for (long id : new long[]{1, 1, 2, 3}) {
            transactions.execute(status -> orderService.redeemStampReward(id));
        }
        assertEquals(List.of(true, false, false), jdbc.queryForList("SELECT stamp_reward_redeemed FROM orders ORDER BY id", Boolean.class));
    }

}
