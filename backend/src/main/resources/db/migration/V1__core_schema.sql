-- Customer Trace — core schema (V1, part 1: identity).
--
-- Conventions:
--   * Additive only. Never drop or rewrite existing objects; add a new versioned migration.
--   * Money is BIGINT paise (never FLOAT/DOUBLE) to avoid rounding drift.
--   * Timestamps are DATETIME(6) in UTC. Monthly promo restrictions are evaluated against
--     `visit_date` (a DATE), which is timezone-free by construction.

-- ───────────────────────────── users & roles ─────────────────────────────
CREATE TABLE roles (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    code        VARCHAR(32)  NOT NULL,
    name        VARCHAR(64)  NOT NULL,
    description VARCHAR(255) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_roles_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Roles live in a table, not an enum column, so they are referentially enforced and can be
-- extended without a schema migration.
CREATE TABLE users (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    user_id       VARCHAR(64)  NOT NULL,
    email         VARCHAR(190) NULL,
    password_hash VARCHAR(100) NOT NULL,
    display_name  VARCHAR(120) NOT NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    last_login_at DATETIME(6)  NULL,
    failed_count  INT          NOT NULL DEFAULT 0,
    locked_until  DATETIME(6)  NULL,
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_user_id (user_id),
    UNIQUE KEY uk_users_email (email),
    KEY idx_users_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE user_roles (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, role_id),
    KEY idx_user_roles_role (role_id),
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_roles_role FOREIGN KEY (role_id) REFERENCES roles (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Only a BCrypt hash of the refresh token is stored, so a database leak yields no usable
-- sessions. family_id groups a rotation chain for reuse detection.
CREATE TABLE refresh_tokens (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    user_id    BIGINT      NOT NULL,
    token_hash VARCHAR(100) NOT NULL,
    family_id  CHAR(36)    NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_refresh_token_hash (token_hash),
    KEY idx_refresh_family (family_id),
    KEY idx_refresh_user (user_id),
    CONSTRAINT fk_refresh_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- ─────────────────────── restaurant & creator profiles ───────────────────────
CREATE TABLE restaurants (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    user_id    BIGINT       NOT NULL,
    name       VARCHAR(160) NOT NULL,
    slug       VARCHAR(180) NOT NULL,
    city       VARCHAR(80)  NOT NULL,
    region     VARCHAR(80)  NOT NULL,
    address    VARCHAR(255) NOT NULL,
    cuisine    VARCHAR(255) NULL,
    phone      VARCHAR(40)  NULL,
    price_level TINYINT     NOT NULL DEFAULT 2,
    -- Running rating aggregates, updated transactionally with each review.
    rating_sum   INT        NOT NULL DEFAULT 0,
    rating_count INT        NOT NULL DEFAULT 0,
    image_url  VARCHAR(500) NULL,
    status     VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6)  NOT NULL,
    updated_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_restaurants_user (user_id),
    UNIQUE KEY uk_restaurants_slug (slug),
    KEY idx_restaurants_city (city),
    KEY idx_restaurants_status (status),
    CONSTRAINT fk_restaurants_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_restaurants_price CHECK (price_level BETWEEN 1 AND 4)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE creators (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    user_id      BIGINT       NOT NULL,
    handle       VARCHAR(80)  NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    city         VARCHAR(80)  NULL,
    niche        VARCHAR(255) NULL,
    followers    INT          NOT NULL DEFAULT 0,
    avatar_url   VARCHAR(500) NULL,
    verified     BOOLEAN      NOT NULL DEFAULT FALSE,
    status       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at   DATETIME(6)  NOT NULL,
    updated_at   DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_creators_user (user_id),
    UNIQUE KEY uk_creators_handle (handle),
    KEY idx_creators_city (city),
    KEY idx_creators_followers (followers),
    KEY idx_creators_status (status),
    CONSTRAINT fk_creators_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Admin → creator assignment to a restaurant. Explicit join table so the assignment itself
-- carries audit columns (who assigned it, when, whether it is still active).
CREATE TABLE restaurant_creator_assignments (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    restaurant_id BIGINT      NOT NULL,
    creator_id    BIGINT      NOT NULL,
    assigned_by   BIGINT      NULL,
    active        BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at    DATETIME(6) NOT NULL,
    updated_at    DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_assignment_pair (restaurant_id, creator_id),
    KEY idx_assignment_creator (creator_id),
    CONSTRAINT fk_assign_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurants (id) ON DELETE CASCADE,
    CONSTRAINT fk_assign_creator FOREIGN KEY (creator_id) REFERENCES creators (id) ON DELETE CASCADE,
    CONSTRAINT fk_assign_user FOREIGN KEY (assigned_by) REFERENCES users (id) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- ───────────────────────────── bookings ─────────────────────────────
-- A booking is a collaboration/visit REQUEST. Deliberately a separate aggregate from
-- campaigns: many bookings may exist before (or without) any campaign.
CREATE TABLE bookings (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    creator_id    BIGINT       NOT NULL,
    restaurant_id BIGINT       NOT NULL,
    visit_date    DATE         NOT NULL,
    party_size    INT          NOT NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT 'REQUESTED',
    note          VARCHAR(500) NULL,
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_bookings_restaurant (restaurant_id, status),
    KEY idx_bookings_creator (creator_id, status),
    KEY idx_bookings_visit_date (visit_date),
    CONSTRAINT fk_bookings_creator FOREIGN KEY (creator_id) REFERENCES creators (id) ON DELETE CASCADE,
    CONSTRAINT fk_bookings_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurants (id) ON DELETE CASCADE,
    CONSTRAINT ck_bookings_party CHECK (party_size BETWEEN 1 AND 100)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE booking_status_history (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    booking_id  BIGINT      NOT NULL,
    from_status VARCHAR(16) NULL,
    to_status   VARCHAR(16) NOT NULL,
    changed_by  BIGINT      NULL,
    reason      VARCHAR(400) NULL,
    changed_at  DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_booking_history (booking_id, changed_at),
    CONSTRAINT fk_booking_hist_booking FOREIGN KEY (booking_id) REFERENCES bookings (id) ON DELETE CASCADE,
    CONSTRAINT fk_booking_hist_user FOREIGN KEY (changed_by) REFERENCES users (id) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- ───────────────────────────── campaigns ─────────────────────────────
-- DRAFT -> PENDING -> (ADMIN_APPROVED|ADMIN_REJECTED) + (RESTAURANT_APPROVED|RESTAURANT_REJECTED)
-- -> ACTIVE only when BOTH approvals are recorded. Enforced in CampaignWorkflowService, not
-- by a trigger, so the rule is unit-testable.
CREATE TABLE campaigns (
    id                 BIGINT        NOT NULL AUTO_INCREMENT,
    creator_id         BIGINT        NOT NULL,
    restaurant_id      BIGINT        NOT NULL,
    title              VARCHAR(160)  NOT NULL,
    description        VARCHAR(1000) NULL,
    offer_text         VARCHAR(255)  NOT NULL,
    discount_percent   INT           NOT NULL,
    min_bill_paise     BIGINT        NULL,
    target_customers   INT           NOT NULL,
    duration_days      INT           NOT NULL,
    start_date         DATE          NOT NULL,
    end_date           DATE          NOT NULL,
    status             VARCHAR(24)   NOT NULL DEFAULT 'DRAFT',
    admin_decision     VARCHAR(16)   NULL,
    restaurant_decision VARCHAR(16)  NULL,
    submitted_at       DATETIME(6)   NULL,
    activated_at       DATETIME(6)   NULL,
    resubmission_count INT           NOT NULL DEFAULT 0,
    -- Optimistic locking: two approvers cannot interleave a silent overwrite.
    version            BIGINT        NOT NULL DEFAULT 0,
    created_at         DATETIME(6)   NOT NULL,
    updated_at         DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    KEY idx_campaigns_creator (creator_id, status),
    KEY idx_campaigns_restaurant (restaurant_id, status),
    KEY idx_campaigns_status (status),
    KEY idx_campaigns_dates (start_date, end_date),
    CONSTRAINT fk_campaigns_creator FOREIGN KEY (creator_id) REFERENCES creators (id) ON DELETE CASCADE,
    CONSTRAINT fk_campaigns_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurants (id) ON DELETE CASCADE,
    CONSTRAINT ck_campaigns_discount CHECK (discount_percent BETWEEN 0 AND 100),
    CONSTRAINT ck_campaigns_target CHECK (target_customers BETWEEN 10 AND 99),
    CONSTRAINT ck_campaigns_duration CHECK (duration_days BETWEEN 1 AND 31),
    CONSTRAINT ck_campaigns_dates CHECK (end_date >= start_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Each party records its own decision separately. The unique key on (campaign, party) makes
-- a double-approval by the same party impossible rather than merely discouraged.
CREATE TABLE campaign_decisions (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    campaign_id BIGINT      NOT NULL,
    -- ADMIN or RESTAURANT — the deciding party, never trusted from the request body alone.
    party       VARCHAR(16) NOT NULL,
    decision    VARCHAR(16) NOT NULL,
    reason      VARCHAR(400) NULL,
    decided_by  BIGINT      NOT NULL,
    created_at  DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_campaign_party (campaign_id, party),
    KEY idx_campaign_decisions (campaign_id, created_at),
    CONSTRAINT fk_decision_campaign FOREIGN KEY (campaign_id) REFERENCES campaigns (id) ON DELETE CASCADE,
    CONSTRAINT fk_decision_user FOREIGN KEY (decided_by) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_decision_party CHECK (party IN ('ADMIN', 'RESTAURANT')),
    CONSTRAINT ck_decision_value CHECK (decision IN ('APPROVED', 'REJECTED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE campaign_status_history (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    campaign_id BIGINT      NOT NULL,
    from_status VARCHAR(24) NULL,
    to_status   VARCHAR(24) NOT NULL,
    changed_by  BIGINT      NULL,
    reason      VARCHAR(400) NULL,
    changed_at  DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_campaign_history (campaign_id, changed_at),
    CONSTRAINT fk_campaign_hist_campaign FOREIGN KEY (campaign_id) REFERENCES campaigns (id) ON DELETE CASCADE,
    CONSTRAINT fk_campaign_hist_user FOREIGN KEY (changed_by) REFERENCES users (id) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
