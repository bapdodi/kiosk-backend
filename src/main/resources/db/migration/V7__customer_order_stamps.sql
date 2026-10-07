-- 적립은 신규 주문부터 시작한다. 주문 재시도에는 저장된 도장 결과를 그대로 반환한다.
CREATE TABLE customer_stamp_accounts (
    customer_code varchar(32) PRIMARY KEY,
    total_stamps bigint NOT NULL CHECK (total_stamps >= 0)
);
ALTER TABLE orders ADD COLUMN stamp_count integer CHECK (stamp_count BETWEEN 1 AND 5);
ALTER TABLE orders ADD COLUMN stamp_reward_earned boolean NOT NULL DEFAULT false;
ALTER TABLE orders ADD COLUMN stamp_reward_redeemed boolean NOT NULL DEFAULT false;
