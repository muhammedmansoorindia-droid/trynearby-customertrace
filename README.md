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

**Campaign workflow, enforced server-side:** a submitted campaign becomes `ACTIVE` only after
**both** the TryNearby administrator and the restaurant approve. Either party rejecting makes it
`REJECTED`, and it can no longer be approved. Each decision, its author, timestamp and reason are
stored separately, and the deciding party is fixed by the route so neither can act for the other.

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

* **Redemption and scan endpoints are not implemented.** The schema, entities and repositories for
  `scan_events`, `redemptions` and the promo tier ledger exist, but there is no REST layer and no
  verified-redemption flow. The frontend scanner still reads the local demo store.
* **Reports and analytics endpoints are not implemented** for any of the three portals.
* **Bookings, reviews, subscriptions and creator assignment** have schema support only.
* **The frontend is not yet wired to the backend.** Screens still read the local demo store, and
  the three-button portal selection plus sign-in pages are still pending.
* Payment processing is intentionally not integrated; `subscriptions.external_ref` is reserved for
  a future provider.