-- Customer Trace — core schema (V2: promo codes, scans, redemptions, subscriptions,
-- reviews, notifications, audit).
--
-- Continues V1 as a separate migration so a future additive change to any of these areas can
-- ship without touching the identity schema.

-- ───────────────────────────── promo codes ─────────────────────────────
CREATE TABLE promo_codes (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    campaign_id   BIGINT      NOT NULL,
    code          CHAR(7)     NOT NULL,
    -- Denormalised from the code so monthly E-J restrictions can be enforced with an index
    -- rather than a scan. client_target and duration_days mirror the NN/DD segments.
    client_target INT         NOT NULL,
    duration_days INT         NOT NULL,
    tier_letter   CHAR(1)     NOT NULL,
    -- TRUE for E-J: redeemable once per restaurant per calendar month.
    exclusive_tier BOOLEAN    NOT NULL DEFAULT FALSE,
    active        BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at    DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_promo_code (code),
    KEY idx_promo_campaign (campaign_id),
    KEY idx_promo_tier (tier_letter, exclusive_tier),
    CONSTRAINT fk_promo_campaign FOREIGN KEY (campaign_id) REFERENCES campaigns (id) ON DELETE CASCADE,
    -- Format LLNNDDX, enforced by the database as well as in application code.
    CONSTRAINT ck_promo_format CHECK (code REGEXP '^[A-Z]{2}[1-9][0-9](0[1-9]|[12][0-9]|3[01])[A-J]$'),
    CONSTRAINT ck_promo_target CHECK (client_target BETWEEN 10 AND 99),
    CONSTRAINT ck_promo_duration CHECK (duration_days BETWEEN 1 AND 31),
    CONSTRAINT ck_promo_tier CHECK (tier_letter BETWEEN 'A' AND 'J')
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Ledger of tier letters consumed per restaurant per calendar month. The primary key makes
-- concurrent E-J allocation for the same (restaurant, month, tier) impossible to duplicate:
-- a second insert fails on the key instead of silently granting a second code.
CREATE TABLE promo_tier_ledger (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    restaurant_id  BIGINT      NOT NULL,
    -- YYYY-MM, derived from visit_date (a DATE ⇒ timezone-free).
    calendar_month CHAR(7)    NOT NULL,
    tier_letter    CHAR(1)    NOT NULL,
    promo_code_id  BIGINT      NULL,
    created_at     DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_tier_restaurant_month (restaurant_id, calendar_month, tier_letter),
    KEY idx_tier_month (calendar_month),
    CONSTRAINT fk_tier_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurants (id) ON DELETE CASCADE,
    CONSTRAINT fk_tier_promo FOREIGN KEY (promo_code_id) REFERENCES promo_codes (id) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- ───────────────────────── scans & redemptions ─────────────────────────
-- A scan is INTENT (a code was presented). It is NOT a verified customer and is never
-- counted toward the customer cap. Invalid scans are retained with valid = FALSE so misuse
-- stays auditable without polluting the analytics funnel.
CREATE TABLE scan_events (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    campaign_id      BIGINT       NOT NULL,
    restaurant_id    BIGINT       NOT NULL,
    -- QR or MANUAL — how the code reached us, reported separately in analytics.
    method           VARCHAR(16)  NOT NULL,
    promo_code       CHAR(7)      NOT NULL,
    valid            BOOLEAN      NOT NULL DEFAULT TRUE,
    rejection_reason VARCHAR(64)  NULL,
    -- Optional, non-identifying device label. Deliberately no customer PII.
    device_label     VARCHAR(120) NULL,
    scanned_by       BIGINT       NULL,
    scanned_at       DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_scans_campaign (campaign_id, scanned_at),
    KEY idx_scans_restaurant (restaurant_id, scanned_at),
    KEY idx_scans_method (method),
    CONSTRAINT fk_scans_campaign FOREIGN KEY (campaign_id) REFERENCES campaigns (id) ON DELETE CASCADE,
    CONSTRAINT fk_scans_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurants (id) ON DELETE CASCADE,
    CONSTRAINT fk_scans_user FOREIGN KEY (scanned_by) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT ck_scans_method CHECK (method IN ('QR', 'MANUAL'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
-- A redemption is a VERIFIED VISIT, explicitly confirmed by staff at bill settlement.
-- Distinct customers are counted with COUNT(DISTINCT customer_ref); NULL refs are anonymous
-- visits and are excluded from the "verified customers" metric.
CREATE TABLE redemptions (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    campaign_id   BIGINT       NOT NULL,
    restaurant_id BIGINT       NOT NULL,
    creator_id    BIGINT       NOT NULL,
    promo_code_id BIGINT       NOT NULL,
    promo_code    CHAR(7)      NOT NULL,
    scan_event_id BIGINT       NULL,
    customer_ref  VARCHAR(80)  NULL,
    visit_date    DATE         NOT NULL,
    bill_paise    BIGINT       NOT NULL,
    discount_paise BIGINT      NOT NULL,
    channel       VARCHAR(16)  NOT NULL DEFAULT 'DINE_IN',
    table_ref     VARCHAR(40)  NULL,
    -- A walk-in that never scanned has NULL scan_event_id; otherwise a scan yields at most
    -- one verified visit, enforced by the unique key.
    scan_redeemed BOOLEAN      NOT NULL DEFAULT FALSE,
    redeemed_by   BIGINT       NOT NULL,
    staff_name    VARCHAR(120) NULL,
    redeemed_at   DATETIME(6)  NOT NULL,
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_redemption_scan (scan_event_id),
    -- Idempotency: the same customer cannot redeem the same campaign twice on one day.
    UNIQUE KEY uk_redemption_daily (campaign_id, customer_ref, visit_date),
    KEY idx_redemptions_campaign (campaign_id, visit_date),
    KEY idx_redemptions_restaurant (restaurant_id, visit_date),
    KEY idx_redemptions_creator (creator_id),
    CONSTRAINT fk_redemptions_campaign FOREIGN KEY (campaign_id) REFERENCES campaigns (id) ON DELETE CASCADE,
    CONSTRAINT fk_redemptions_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurants (id) ON DELETE CASCADE,
    CONSTRAINT fk_redemptions_creator FOREIGN KEY (creator_id) REFERENCES creators (id) ON DELETE CASCADE,
    CONSTRAINT fk_redemptions_promo FOREIGN KEY (promo_code_id) REFERENCES promo_codes (id) ON DELETE RESTRICT,
    CONSTRAINT fk_redemptions_scan FOREIGN KEY (scan_event_id) REFERENCES scan_events (id) ON DELETE SET NULL,
    CONSTRAINT fk_redemptions_user FOREIGN KEY (redeemed_by) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_redemptions_channel CHECK (channel IN ('DINE_IN', 'TAKEAWAY', 'DELIVERY')),
    CONSTRAINT ck_redemptions_amounts CHECK (bill_paise >= 0 AND discount_paise >= 0 AND discount_paise <= bill_paise)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
-- ───────────────────────── subscriptions ─────────────────────────
CREATE TABLE subscription_plans (
    id                  BIGINT      NOT NULL AUTO_INCREMENT,
    code                VARCHAR(32) NOT NULL,
    name                VARCHAR(80) NOT NULL,
    monthly_price_paise BIGINT      NOT NULL DEFAULT 0,
    max_campaigns       INT         NOT NULL DEFAULT 0,
    features            VARCHAR(500) NULL,
    active              BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at          DATETIME(6) NOT NULL,
    updated_at          DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_plan_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE subscriptions (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    restaurant_id  BIGINT      NOT NULL,
    plan_id        BIGINT      NOT NULL,
    status         VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    billing_cycle  VARCHAR(16) NOT NULL DEFAULT 'MONTHLY',
    amount_paise   BIGINT      NOT NULL,
    started_at     DATETIME(6) NOT NULL,
    renews_at      DATETIME(6) NULL,
    cancelled_at   DATETIME(6) NULL,
    -- Payment integration is a future extension; NULL until a provider is configured.
    external_ref   VARCHAR(120) NULL,
    created_at     DATETIME(6) NOT NULL,
    updated_at     DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_subs_restaurant (restaurant_id, status),
    CONSTRAINT fk_subs_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurants (id) ON DELETE CASCADE,
    CONSTRAINT fk_subs_plan FOREIGN KEY (plan_id) REFERENCES subscription_plans (id) ON DELETE RESTRICT,
    CONSTRAINT ck_subs_status CHECK (status IN ('TRIALING', 'ACTIVE', 'PAST_DUE', 'CANCELLED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE subscription_history (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    subscription_id BIGINT      NOT NULL,
    from_status     VARCHAR(16) NULL,
    to_status       VARCHAR(16) NOT NULL,
    changed_by      BIGINT      NULL,
    note            VARCHAR(400) NULL,
    changed_at      DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_sub_history (subscription_id, changed_at),
    CONSTRAINT fk_subhist_sub FOREIGN KEY (subscription_id) REFERENCES subscriptions (id) ON DELETE CASCADE,
    CONSTRAINT fk_subhist_user FOREIGN KEY (changed_by) REFERENCES users (id) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- ───────────────────────── reviews, notifications, audit ─────────────────────────
CREATE TABLE reviews (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    creator_id    BIGINT        NOT NULL,
    restaurant_id BIGINT        NOT NULL,
    campaign_id   BIGINT        NULL,
    rating        TINYINT       NOT NULL,
    body          VARCHAR(1000) NULL,
    created_at    DATETIME(6)   NOT NULL,
    updated_at    DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    -- One review per creator/restaurant/campaign. NULL campaign_id is permitted (general
    -- rating) — MySQL treats NULLs as distinct in unique keys, so the service guards it.
    UNIQUE KEY uk_review_creator_restaurant_campaign (creator_id, restaurant_id, campaign_id),
    KEY idx_reviews_restaurant (restaurant_id),
    KEY idx_reviews_creator (creator_id),
    CONSTRAINT fk_reviews_creator FOREIGN KEY (creator_id) REFERENCES creators (id) ON DELETE CASCADE,
    CONSTRAINT fk_reviews_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurants (id) ON DELETE CASCADE,
    CONSTRAINT fk_reviews_campaign FOREIGN KEY (campaign_id) REFERENCES campaigns (id) ON DELETE SET NULL,
    CONSTRAINT ck_reviews_rating CHECK (rating BETWEEN 1 AND 5)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE notifications (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    type         VARCHAR(48)  NOT NULL,
    -- ADMIN | RESTAURANT | CREATOR — audience, used to fan out per role.
    audience     VARCHAR(16)  NOT NULL,
    recipient_id BIGINT       NULL,
    title        VARCHAR(200) NOT NULL,
    body         VARCHAR(600) NULL,
    link         VARCHAR(255) NULL,
    read_flag    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at   DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_notifications_recipient (recipient_id, read_flag, created_at),
    KEY idx_notifications_audience (audience, created_at),
    CONSTRAINT fk_notif_recipient FOREIGN KEY (recipient_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Append-only audit trail for sensitive administrative and campaign-decision actions.
CREATE TABLE audit_logs (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    actor_id      BIGINT        NULL,
    actor_user_id VARCHAR(64)   NULL,
    action        VARCHAR(64)   NOT NULL,
    entity_type   VARCHAR(48)   NOT NULL,
    entity_id     VARCHAR(64)   NULL,
    detail        VARCHAR(1000) NULL,
    ip_address    VARCHAR(64)   NULL,
    created_at    DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    KEY idx_audit_entity (entity_type, entity_id),
    KEY idx_audit_actor (actor_id),
    KEY idx_audit_created (created_at),
    CONSTRAINT fk_audit_actor FOREIGN KEY (actor_id) REFERENCES users (id) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
