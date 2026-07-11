ALTER TABLE order_info
    ADD COLUMN pay_expire_at DATETIME NULL AFTER source_id;

CREATE INDEX idx_order_source_status_expire
    ON order_info (source, status, pay_expire_at);
