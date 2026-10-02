# Customer Trace

A platform that connects content creators with restaurants and tracks the customers each creator
campaign actually brings in.

> **Branding:** the product is **Customer Trace**. The TryNearby administrator portal keeps the
> name *TryNearby*, as agreed.

## Architecture

A modular monolith plus a React SPA.

```
customer-trace/
├── src/                     React 19 + TypeScript + Vite + Tailwind (Stitch design system)
│   ├── app/                 Portal screens, routing, Zustand store
│   ├── services/            Promo-code + metrics logic (frontend demo copies)
│   └── types/               Shared domain types
├── backend/                 Java 21 + Spring Boot 3.4 (controller, service, repo, domain)
│   └── src/main/resources/db/migration/   Flyway migrations (V1, V2)
├── docker-compose.yml       MySQL 8 + backend
└── .env.example             Placeholders only — copy to .env
```

Layering (backend): `web/controller` → `service` → `repository` → `domain`. Controllers hold no
business logic, and entities are never exposed directly — every response is a DTO.

### Database

22 tables, created by Flyway, authoritative in MySQL 8. Notable constraints:

| Constraint | Purpose |
|---|---|
| `redemptions.uk_redemption_scan` | a scan yields at most one verified visit |
| `redemptions.uk_redemption_daily` | no same-customer, same-campaign, same-day double redemption |
| `promo_tier_ledger.uk_tier_restaurant_month` | E–J tier once per restaurant per calendar month |
| `campaign_decisions.uk_campaign_party` | one decision per party per campaign |
| `promo_codes.ck_promo_format` | the `LLNNDDX` shape is enforced in the database too |

### Authentication design

* **Access token** — short-lived (15 min) JWT in the `Authorization` header, held in memory by
  the frontend. Never written to localStorage.
* **Refresh token** — long-lived, delivered as an **HttpOnly** cookie (path `/api/auth`) and
  stored server-side only as a **BCrypt hash**. Rotated on every use; replaying a rotated token
  revokes the whole family (reuse detection).
* **CSRF** — the session is stateless and the access token is not an ambient credential, so the
  only cookie-authenticated routes (`/api/auth/refresh`, `/api/auth/logout`) additionally require
  an `X-CT-CSRF` header that a cross-origin form post cannot forge.
* **Brute force** — consecutive failures increment a counter and lock the account for a
  configurable window.
* **Roles** — read from `user_roles`. The `Trynearby` / `restrnt` / `creator` id suffixes are a
  naming convention for development accounts only and never grant access.

### Timezone policy

Campaign dates and promo monthly limits are evaluated against `DATE` columns (`visit_date`,
`start_date`), which carry no timezone, so a calendar month means the same thing to every caller.
Audit and event timestamps are `DATETIME(6)` stored in UTC.

## Prerequisites

* Java 21 (verified on 21.0.12)
* Maven 3.9+
* Node 20+ and npm
* Docker (for the MySQL container)
## Environment variables

Copy `.env.example` to `.env` and fill in the values. The application **refuses to start**
without a valid `JWT_SECRET` (at least 32 bytes, not the documented placeholder).

```bash
cp .env.example .env
openssl rand -base64 48        # paste the result into JWT_SECRET
```

## Running locally

### Option A — Docker Compose for MySQL and the backend

```bash
cp .env.example .env      # edit JWT_SECRET
docker compose up -d
docker compose logs -f backend
curl http://localhost:8080/actuator/health
```

Then run the frontend on the host:

```bash
npm install
npm run dev               # http://localhost:5173
```

### Option B — everything local

Start MySQL on any free port, then:

```bash
cd backend
mvn clean package
JWT_SECRET="$(openssl rand -base64 48)" \
DEV_ACCOUNT_PASSWORD='your-local-password' \
BOOTSTRAP_ENABLED=true \
java -jar target/backend-1.0.0.jar
```

The frontend reads the API base URL from `VITE_API_BASE_URL`, defaulting to
`http://localhost:8080`.

### Database migrations

Flyway runs automatically on startup. To apply them by hand:

```bash
cd backend && mvn flyway:migrate
```

Migrations are **additive only**. To remove the stack:

```bash
docker compose down        # keeps the volume
docker compose down -v     # explicit, destroys data
```

## Development accounts

Created only when `BOOTSTRAP_ENABLED=true` **and** `DEV_ACCOUNT_PASSWORD` is set. Passwords are
BCrypt-hashed at startup and never stored in clear text. Seeding is idempotent — restarting does
not duplicate accounts or reset a changed password.

| User ID | Role |
|---|---|
| `admin@Trynearby` | TryNearby administrator |
| `copper@restrnt` | Restaurant — Copper Chimney, Coimbatore |
| `malabar@restrnt` | Restaurant — The Malabar Table, Palakkad |
| `pooja@creator` | Creator |
| `aiswarya@creator` | Creator |

There is **no public admin registration endpoint**.

## API documentation

With the backend running:

* Swagger UI — <http://localhost:8080/swagger-ui.html>
* OpenAPI JSON — <http://localhost:8080/v3/api-docs>

## Implemented endpoints

| Area | Endpoints |
|---|---|
| Auth | `POST /api/auth/login`, `/refresh`, `/logout`; `GET /api/auth/me` |
| Creator campaigns | `POST /api/creator/campaigns`; `GET` list and `/{id}`; `POST /{id}/submit`; `GET /{id}/history` |
| Admin campaigns | `GET /api/admin/campaigns`, `/{id}`, `/{id}/history`; `POST /{id}/approve`, `/{id}/reject` |
| Restaurant campaigns | `GET /api/restaurants/campaigns`, `/{id}`, `/{id}/history`; `POST /{id}/approve`, `/{id}/reject` |
| Scans & redemptions | `GET /api/restaurants/scan/lookup`; `POST /api/restaurants/scan/scan`; `POST /api/restaurants/scan/redeem` |

**Campaign workflow, enforced server-side:** a submitted campaign becomes `ACTIVE` only after
**both** the TryNearby administrator and the restaurant approve. Either party rejecting makes it
`REJECTED`, and it can no longer be approved. Each decision, its author, timestamp and reason are
stored separately, and the deciding party is fixed by the route so neither can act for the other.

**Scans versus redemptions:** `POST /scan` records interest only — it never creates a
redemption and never changes the verified-customer count. `POST /redeem` is the only call that
produces a verified customer visit, and it is what enforces the campaign limit, the validity
window and the E–J monthly tier rule.

### Example: a full campaign lifecycle

```bash
# 1. Creator creates a draft and submits it (a promo code is generated on submit)
curl -X POST localhost:8080/api/creator/campaigns -H "Authorization: Bearer $CREATOR" \
  -H 'Content-Type: application/json' \
  -d '{"restaurantId":1,"title":"Weekend Biryani","offerText":"Flat 15% Off",
       "discountPercent":15,"targetCustomers":50,"durationDays":7,
       "startDate":"2026-09-01","endDate":"2026-12-31"}'

curl -X POST localhost:8080/api/creator/campaigns/1/submit -H "Authorization: Bearer $CREATOR"

# 2. TryNearby approves (status stays PENDING until the restaurant also approves)
curl -X POST localhost:8080/api/admin/campaigns/1/approve -H "Authorization: Bearer $ADMIN"

# 3. The restaurant approves — now the campaign is ACTIVE
curl -X POST localhost:8080/api/restaurants/campaigns/1/approve -H "Authorization: Bearer $RESTAURANT"

# 4. Look the code up, scan it, then redeem it
curl "localhost:8080/api/restaurants/scan/lookup?code=<CODE>" -H "Authorization: Bearer $RESTAURANT"
curl -X POST localhost:8080/api/restaurants/scan/scan -H "Authorization: Bearer $RESTAURANT" \
  -H 'Content-Type: application/json' -d '{"promoCode":"<CODE>","method":"QR"}'
curl -X POST localhost:8080/api/restaurants/scan/redeem -H "Authorization: Bearer $RESTAURANT" \
  -H 'Content-Type: application/json' \
  -d '{"promoCode":"<CODE>","customerRef":"guest-1","billPaise":200000,"discountPaise":30000}'
```

## Tests

```bash
# Frontend
npm run test              # vitest
npx tsc -b                # type check
npm run build             # production build

# Backend
cd backend
mvn clean test
mvn clean package
```

## Known limitations

Stated plainly so nothing here is mistaken for finished work:

* **Portal dashboards still read the local demo store.** Sign-in, the API client and the
  campaign/scan endpoints are wired to the backend, but the dashboard, campaign-list and scanner
  screens have not been migrated from their local demo data to API calls.
* **Reports and analytics endpoints are not implemented** for any of the three portals.
* **Bookings, reviews, subscriptions, creator assignment and notifications** have schema support
  only; the REST layers are still to come, so those UI screens remain demo-backed.
* **Backend integration tests are not yet written.** The promo-code rules have real unit tests;
  the dual-approval and redemption flows have been verified against a running server and MySQL
  but are not yet covered by automated Spring tests.
* Payment processing is intentionally not integrated; `subscriptions.external_ref` is reserved for
  a future provider.