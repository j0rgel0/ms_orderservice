-- V1__Create_Orders_and_Order_Items_Tables.sql

CREATE TYPE cancellation_reason_enum AS ENUM (
    'INVENTORY_PRODUCT_NOT_AVAILABLE',
    'INVENTORY_PRODUCT_NOT_EXIST',
    'INVENTORY_SERVICE_UNRESPONSIVE',
    'PAYMENT_INSUFFICIENT_FUNDS'
    );

CREATE TABLE IF NOT EXISTS orders
(
    order_id            UUID PRIMARY KEY            DEFAULT gen_random_uuid(),
    track_id            UUID           NULL,
    user_id             UUID           NOT NULL,
    total_amount        NUMERIC(10, 2) NULL,
    currency            VARCHAR(3)     NOT NULL,
    status              VARCHAR(20)    NOT NULL,
    cancellation_reason cancellation_reason_enum NULL,
    created_at          TIMESTAMP WITHOUT TIME ZONE DEFAULT (NOW() AT TIME ZONE 'UTC'),
    updated_at          TIMESTAMP WITHOUT TIME ZONE DEFAULT (NOW() AT TIME ZONE 'UTC')
);

CREATE TABLE IF NOT EXISTS order_items
(
    order_item_id UUID PRIMARY KEY            DEFAULT gen_random_uuid(),
    order_id      UUID           NOT NULL,
    product_id    UUID           NOT NULL,
    quantity      INTEGER        NOT NULL CHECK (quantity > 0),
    price         NUMERIC(10, 2) NOT NULL,
    created_at    TIMESTAMP WITHOUT TIME ZONE DEFAULT (NOW() AT TIME ZONE 'UTC'),
    updated_at    TIMESTAMP WITHOUT TIME ZONE DEFAULT (NOW() AT TIME ZONE 'UTC'),
    CONSTRAINT fk_order
        FOREIGN KEY (order_id)
            REFERENCES orders (order_id)
            ON DELETE CASCADE
);
