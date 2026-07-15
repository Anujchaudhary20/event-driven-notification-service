CREATE TABLE notifications (
    id                BIGSERIAL PRIMARY KEY,
    idempotency_key   VARCHAR(255) NOT NULL,
    user_id           VARCHAR(255) NOT NULL,
    channel           VARCHAR(50)  NOT NULL,
    payload           JSONB        NOT NULL,
    state             VARCHAR(50)  NOT NULL DEFAULT 'PENDING',
    retry_count       INT          NOT NULL DEFAULT 0,
    last_error        VARCHAR(2000),
    created_at        TIMESTAMPTZ  NOT NULL,
    updated_at        TIMESTAMPTZ,
    CONSTRAINT uk_notifications_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT chk_notifications_channel CHECK (channel IN ('EMAIL', 'SMS')),
    CONSTRAINT chk_notifications_state CHECK (state IN ('PENDING', 'PROCESSING', 'SENT', 'RETRYING', 'FAILED'))
);

CREATE INDEX idx_notifications_user_id   ON notifications (user_id);
CREATE INDEX idx_notifications_state     ON notifications (state);
CREATE INDEX idx_notifications_created_at ON notifications (created_at);

CREATE TABLE dead_letter_notifications (
    id               BIGSERIAL PRIMARY KEY,
    notification_id  BIGINT       NOT NULL,
    user_id          VARCHAR(255) NOT NULL,
    channel          VARCHAR(50)  NOT NULL,
    payload          JSONB        NOT NULL,
    failure_reason   VARCHAR(2000) NOT NULL,
    retry_count      INT          NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL,
    CONSTRAINT chk_dlq_channel CHECK (channel IN ('EMAIL', 'SMS'))
);

CREATE INDEX idx_dlq_notification_id ON dead_letter_notifications (notification_id);
CREATE INDEX idx_dlq_created_at      ON dead_letter_notifications (created_at);
