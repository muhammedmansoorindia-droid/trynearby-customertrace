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
| `promo_tier_ledger.uk_tier_restaurant_month` | G–J discount (7–10%) once per restaurant per calendar month |
| `campaign_decisions.uk_campaign_party` | one decision per party per campaign |
| `promo_codes.ck_promo_format` | the `LLNNDDX` shape is enforced in the database too |

### Authentication design

* **Access token** — short-lived (15 min) JWT in the `Authorization` header, held in memory by
  the frontend. Never written to localStorage.
* **Refresh token** — long-lived, delivered as an **HttpOnly** cookie (path `/api/auth`) and
  stored server-side only as a **SHA-256 digest**. Rotated on every use; replaying a rotated token
  revokes the whole family (reuse detection). The token is server-minted and high-entropy, so a
  fast digest is the right tool — and it keeps the rotation lookup an indexed equality match.
  (BCrypt cannot be used here: it ignores input past 72 bytes, and the JWT is roughly 330
  characters, so every token of a user would collapse to the same prefix.)
* **CSRF** — the session is stateless and the access token is not an ambient credential, so the
  only cookie-authenticated routes (`/api/auth/refresh`, `/api/auth/logout`) require **both** an
  `X-CT-CSRF` header and an `X-XSRF-TOKEN` echo of the readable `XSRF-TOKEN` cookie that login
  issues. Either check alone is forgeable; together they mean the request came from script that
  could read the cookie, which a cross-origin page cannot do. The refresh cookie itself stays
  HttpOnly.
* **Brute force** — consecutive failures increment a counter and lock the account for a
  configurable window.
* **Roles** — read from `user_roles`. The `Trynearby` / `restrnt` / `creator` id suffixes are a
  naming convention for development accounts only and never grant access.

### Timezone policy

Campaign dates and promo monthly limits are evaluated against `DATE` columns (`visit_date`,
`start_date`), which carry no timezone, so a calendar month means the same thing to every caller.
Audit and event timestamps are `DATETIME(6)` stored in UTC.

### Profile status versus assignments

Three things are deliberately independent, and confusing them makes the admin portal misleading:

* **Profile status** (`restaurants.status`, `creators.status`) — `ACTIVE` or `SUSPENDED`, set by an
  administrator under `/trynearby/directory`. Suspending a venue or creator stops new work but
  deletes nothing: campaigns, bookings, reviews and assignment rows all survive, so reactivating is
  a true undo.
* **Assignment status** (`restaurant_creator_assignments.active`) — whether one creator may run a
  campaign for one venue. This is what the creator's New Campaign wizard filters on, and what the
  campaign endpoint re-checks server-side.
* **Subscription status** — whether a venue is entitled. Governed under `/trynearby/subscriptions`.

Suspending a profile therefore does **not** end an assignment, and ending an assignment does **not**
suspend anyone. An unknown status is rejected rather than stored.

### Searching, filtering and ordering a campaign list

All three campaign list endpoints (`/api/admin/campaigns`, `/api/creator/campaigns`,
`/api/restaurants/campaigns`) accept the same three optional parameters, and answer them in the
database:

| Parameter | Meaning |
|---|---|
| `status` | One backend `CampaignStatus`. Omit for every status. |
| `q` | Case-insensitive match on the campaign code, the creator's name, or the restaurant's name. |
| `sort` | `newest` (default) or `oldest`, applied to `createdAt`. Case-insensitive. |

Two things are deliberate here:

* **Search is server-side, not client-side.** Filtering the page that happens to be in hand would
  only ever see that page, so a term matching a campaign on page 3 would report "no results" while
  the client sat on page 1.
* **`sort` is a named pair, not a free-text field.** The value is resolved with
  `CampaignSort.valueOf(raw.toUpperCase())`, so a caller cannot order by an arbitrary column, and an
  unrecognised direction is a **400** (`UNKNOWN_SORT`) rather than Spring's default 500.

A restaurant's list is scoped from the access token, never a parameter, so no query string can widen
one venue's queue to include another's.

### Null fields are omitted, not sent as `null`

The application serialises with `default-property-inclusion: non_null`. A nullable field is
therefore **absent** from the JSON rather than present-and-null, so client code must treat "missing"
and "null" alike (`value == null` in TypeScript, `path()` in Jackson). Two places depend on this:

* `RestaurantSummary.averageRating` is absent until a venue has its first rating, so the UI can say
  "no ratings yet" instead of rendering `0.0★` — which would read as a terrible score.
* Resolved display names (`creatorName`, `restaurantName`) are absent when the profile behind them
  has been removed, so a list never shows a blank where a name used to be.

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
python3 scripts/setup-local-env.py   # creates .env (JWT secret, CORS, API URL)
docker compose up -d
docker compose logs -f backend
curl http://localhost:8080/actuator/health
```

`scripts/setup-local-env.py` is idempotent and never prints secrets: it generates a
`JWT_SECRET` only if one is missing, keeps existing DB credentials so the MySQL volume stays
readable, and derives the LAN address from your current network. Re-running it never overwrites
a valid secret (changing it would invalidate every issued token).

Then run the frontend on the host:

```bash
npm install
npm run dev               # http://localhost:5173
```

### Testing from a phone on the same Wi‑Fi

The phone cannot resolve `localhost` (that would be the phone itself), so two things are set in
your **git-ignored** `.env` — never in tracked source:

* `VITE_API_BASE_URL=http://<mac-ip>:8080` — what the phone's browser calls.
* `CORS_ALLOWED_ORIGINS=http://localhost:5173,http://<mac-ip>:5173` — the explicit origin
  allow-list. It is a comma-separated list, never `*`; credentials stay enabled.

`vite.config.ts` binds to `0.0.0.0` (written as `host: true`, not an address) with
`strictPort: true`, so the dev server never silently hops to `:5174` and breaks that allow-list.

Find your address, regenerate the config, then start:

```bash
ipconfig getifaddr en0              # prints your LAN address
python3 scripts/setup-local-env.py  # pick it up into .env (non-destructive)
npm run dev
```

Then open `http://<mac-ip>:5173` on the phone.

If your address changes (new Wi‑Fi, DHCP), re-run the two commands above: `.env` keeps your
`JWT_SECRET`, so no token is invalidated.

Signing in from the phone should survive a page reload. To confirm the cookie round trip from a
terminal before reaching for the phone — it prints the refresh status plus the four ways a request
can still be refused:

```bash
python3 qa/verify-session.py http://<mac-ip>:5173 http://<mac-ip>:8080
```

Your phone needs `REFRESH_COOKIE_SECURE=false` (already the default here) because the dev stack
is plain HTTP; that is correct only for local development. Don't expose this to the public
internet — the backend binds to Docker's published `8080`, and the app is not hardened for
public deployment.

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
`http://localhost:8080`. Set it in `.env.local` when the backend is not on the default port:

```bash
echo 'VITE_API_BASE_URL=http://localhost:8080' > .env.local
```

Then start the dev server:

```bash
npm run dev      # http://localhost:5173
```

**How the session works.** The access token is held in memory only and is never written to
`localStorage`; the HttpOnly refresh cookie is the only thing that survives a reload. On boot the
app calls `/api/auth/refresh` and then `/api/auth/me`, so signing in and restoring a session both
go through the real API. Signing in redirects to `/trynearby`, `/restaurant` or `/creator`
according to the role the backend reports.

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
| Reports | `GET /api/reports/platform/{summary,campaigns,trend}`; `GET /api/reports/restaurant/{summary,campaigns,trend,status-breakdown}`; `GET /api/reports/creator/{summary,campaigns,trend,status-breakdown}` |
| Notifications | `GET /api/notifications`, `/unread-count`; `POST /api/notifications/{id}/read`, `/read-all` |
| Bookings | `POST /api/creator/bookings`; `GET /api/{creator,restaurant,admin}/bookings`, `/{id}`; `PATCH /{id}/status` |
| Reviews | `POST /api/creator/reviews`; `PUT /api/creator/reviews/{id}`; `GET /api/creator/reviews`, `/restaurant/{id}`; `GET /api/restaurant/reviews`; `GET /api/restaurants/{id}/reviews` |
| Subscriptions | `GET /api/restaurant/subscription`, `/subscriptions`; `GET /api/plans`; `PUT /api/admin/subscriptions/{id}/plan`; `PATCH /api/admin/subscriptions/{id}/status` |
| Assignments | `GET /api/restaurant/assignments`, `/api/creator/assignments`, `/api/admin/assignments`, `/api/admin/creators`, `/api/admin/restaurants`; `POST /api/restaurant/assignments`, `/api/admin/assignments`; `DELETE /api/restaurant/assignments/{id}`, `/api/admin/assignments/{id}`; `POST /api/admin/assignments/{id}/reactivate` |
| Platform directory | `GET /api/admin/restaurants`, `/api/admin/creators`, `/api/admin/subscriptions`; `PATCH /api/admin/restaurants/{id}/status`, `/api/admin/creators/{id}/status` |

An active assignment is the only thing that lets a creator run a campaign for a restaurant, and
`POST /api/creator/campaigns` re-checks it server-side — so the wizard hiding a restaurant and the
API refusing to target it are the same rule. Deactivating an assignment stops *new* campaigns;
campaigns, scans and redemptions already recorded are left untouched.

All report routes accept optional `from` and `to` query parameters (`yyyy-MM-dd`, inclusive, max
366 days); an inverted range is a `400` rather than a silent zero.

### Authorization rules

Ownership is re-derived from the authenticated principal on every request — a user-supplied
restaurant or creator id is never trusted. Where a record belongs to another tenant the API answers
`404`, not `403`, so ids cannot be probed.

| Capability | Admin | Restaurant | Creator |
|---|---|---|---|
| Platform reports | yes | no | no |
| Own reports | — | own restaurant only | own campaigns only |
| Bookings | all | own restaurant's | own |
| Confirm / decline / complete a booking | yes | yes (own) | no |
| Cancel a booking | yes | no | yes (own) |
| Create a review | no | no | yes, if assigned |
| Edit a review | no | no | own only |
| Change plan / subscription status | yes | no | no |
| Manage assignments | any | own restaurant only | no |

### State transitions

Transitions are enforced server-side against an explicit allow-list; anything else returns `409
INVALID_STATUS_TRANSITION` and writes nothing.

- **Booking** — `REQUESTED → CONFIRMED | DECLINED | CANCELLED`; `CONFIRMED → COMPLETED |
  CANCELLED`. `COMPLETED`, `CANCELLED` and `DECLINED` are terminal. Every accepted change writes a
  `booking_status_history` row in the same transaction.
- **Subscription** — `TRIALING → ACTIVE | CANCELLED`; `ACTIVE → PAST_DUE | CANCELLED`;
  `PAST_DUE → ACTIVE | CANCELLED`. `CANCELLED` is terminal. The amount always comes from the plan,
  never from the request.
- **Assignment** — a duplicate active pair is refused with `409 ASSIGNMENT_EXISTS`; deactivating an
  already-inactive row is refused rather than silently succeeding.
- **Review** — one review per creator/restaurant/campaign; a creator may edit their own rather than
  posting a second. Eligibility is an active assignment, re-read from the database.

**Campaign workflow, enforced server-side:** a submitted campaign becomes `ACTIVE` only after
**both** the TryNearby administrator and the restaurant approve. Either party rejecting makes it
`REJECTED`, and it can no longer be approved. Each decision, its author, timestamp and reason are
stored separately, and the deciding party is fixed by the route so neither can act for the other.

**Scans versus redemptions:** `POST /scan` records interest only — it never creates a
redemption and never changes the verified-customer count. `POST /redeem` is the only call that
produces a verified customer visit, and it enforces the campaign limit and the validity window.
The monthly discount limit is settled earlier, when the campaign is *submitted*.

### Campaign names and promo codes

A campaign is never named by hand. The server derives the name from the campaign's own settings
using the same rule that issues the promo code, so the two can never disagree:

```
LL NN DD X
│  │  │  └─ discount letter:  B=2%  C=3%  D=4%  E=5%  F=6%  G=7%  H=8%  I=9%  J=10%
│  │  └───── duration in days, 01-99 (two digits)
│  └──────── customer target, 01-99 (two digits)
└─────────── two random uppercase letters
```

`KT2514E` therefore means 25 target customers, 14 days, and a 5% discount. The discount letter is
*derived* from the chosen discount, never picked at random — a 5% campaign cannot end in F or G.

Only the two letters are random. A uniqueness collision re-draws the letters alone; the target,
duration and discount the creator chose are carried through untouched. Once the campaign is
submitted its code is fixed and is never re-derived, so reloading the page or reopening the campaign
always shows the same code the diner holds.

**Monthly discount limit.** B–F (2–6%) are reusable. G–J (7–10%) may each back **at most one
campaign per restaurant per calendar month**, enforced by inserting into `promo_tier_ledger` on
submit — the unique key on `(restaurant_id, calendar_month, tier_letter)` makes two simultaneous
submissions unable to both succeed, so the cap holds under concurrency rather than only in
single-threaded use. Because the key omits `creator_id`, the cap belongs to the venue: several
creators assigned to one restaurant share a single allowance for each high discount. A refused
submission consumes nothing.

The limit governs *campaigns*, not redemptions: a single campaign may still serve its full
customer target.

### Example: a full campaign lifecycle

```bash
# 1. Creator creates a draft and submits it. There is no title: the campaign is named by the
#    generated code, which is issued on submit.
curl -X POST localhost:8080/api/creator/campaigns -H "Authorization: Bearer $CREATOR" \
  -H 'Content-Type: application/json' \
  -d '{"restaurantId":1,"offerText":"Flat 5% Off",
       "discountPercent":5,"targetCustomers":50,"durationDays":7,
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
# Backend — unit + integration tests
cd backend
mvn clean test        # 119 tests
mvn clean verify      # full verification, produces target/backend-1.0.0.jar

# Frontend
npm run test          # 112 tests (vitest); 2 live tests skipped unless QA_LIVE=1
npx tsc -b            # type check
npm run build         # production build
```

### How the backend tests are isolated

Integration tests run against a **separate `customertrace_test` schema**, never your
development data. Create it once:

```bash
docker exec -it ct-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "
  CREATE DATABASE IF NOT EXISTS customertrace_test;
  GRANT ALL PRIVILEGES ON customertrace_test.* TO 'ct'@'%';
  GRANT ALL PRIVILEGES ON customertrace_test.* TO 'ct'@'localhost';"
```

Override the connection with `TEST_DB_URL` / `TEST_DB_USERNAME` / `TEST_DB_PASSWORD`
(defaults in `backend/src/test/resources/application-test.yml`). Every test runs inside a
transaction that is rolled back, so fixtures never leak between tests.

**What is covered:** the `LLNNDDX` format and every B–J discount mapping, code uniqueness and
stability, the monthly discount limit (reuse, one-per-restaurant-month, per-creator sharing,
month boundaries, concurrent submissions, and rejection without consuming the allowance),
authentication (valid/invalid/disabled/locked accounts, tampered and malformed
JWTs, role restrictions), the dual-approval campaign workflow, and the scan-versus-redemption
distinction including duplicate prevention and cross-tenant denial.

### Frontend data flow

Every screen reads from the API through three layers, and none of them falls back to mock data:

* `services/api.ts` — the fetch wrapper. Holds the access token **in memory**, turns any non-2xx
  into an `ApiError` carrying the backend's `code` and `message`, and turns a transport failure
  into an `ApiError` with status `0` so the UI can distinguish "server said no" from "server not
  reachable".
* `services/backend/*` — typed endpoint modules mirroring the Java DTOs exactly, plus
  `toFrontendStatus`, the single place the backend's `UPPER_SNAKE` statuses are translated to the
  vocabulary the UI already used.
* `app/useResource.ts` — `useResource` for reads and `useMutation` for writes. `useResource`
  **clears its data when a request fails**, so a screen can never show yesterday's numbers next to
  an error. `useMutation` blocks a second submit while one is in flight.

`LoadingState`, `EmptyState` and `ErrorState` in `app/ui.tsx` are the only three states a data
screen renders, which is what keeps mock data from creeping back in.

### Screens and the endpoints behind them

| Screen | Reads | Writes |
|---|---|---|
| `/trynearby` | `reports/platform/summary`, `admin/campaigns?status=PENDING` | — |
| `/trynearby/campaigns` | `admin/campaigns` (with `q`, `status`, `sort`) | `admin/campaigns/{id}/approve`, `/reject` |
| `/trynearby/assignments` | `admin/assignments`, `admin/creators`, `admin/restaurants` | `admin/assignments` (POST), `admin/assignments/{id}` (DELETE), `admin/assignments/{id}/reactivate` |
| `/trynearby/directory` | `admin/restaurants`, `admin/creators` | `admin/restaurants/{id}/status`, `admin/creators/{id}/status` |
| `/trynearby/subscriptions` | `admin/subscriptions`, `plans` | `admin/subscriptions/{id}/plan`, `admin/subscriptions/{id}/status` |
| `/restaurant` | `reports/restaurant/summary`, `restaurants/campaigns`, `restaurant/bookings`, `restaurants/{id}/reviews` | — |
| `/restaurant/scan` | `restaurants/scan/lookup` | `restaurants/scan/scan`, `restaurants/scan/redeem` |
| `/restaurant/campaigns` | `restaurants/campaigns` (with `q`, `status`, `sort`), `reports/restaurant/campaigns` | `restaurants/campaigns/{id}/approve`, `/reject` |
| `/restaurant/bookings` | `restaurant/bookings` | `restaurant/bookings/{id}/status` |
| `/restaurant/creators` | `restaurant/assignments` | `restaurant/assignments/{id}` (DELETE) |
| `/restaurant/subscription` | `restaurant/subscription`, `plans` | — (admin-only changes) |
| `/creator` | `reports/creator/summary`, `reports/creator/campaigns`, `creator/campaigns` | — |
| `/creator/campaigns` | `creator/campaigns`, `reports/creator/campaigns` | `creator/campaigns/{id}/submit` |
| `/creator/new` | `creator/assignments` | `creator/campaigns`, `creator/campaigns/{id}/submit` |
| `/creator/bookings` | `creator/bookings` | `creator/bookings`, `creator/bookings/{id}/status` |
| `/creator/reviews` | `creator/reviews`, `creator/assignments` | `creator/reviews`, `creator/reviews/{id}` |
| `/notifications` | `notifications`, `notifications/unread-count` | `notifications/{id}/read`, `notifications/read-all` |

### Tests that need a running backend

`src/app/live.integration.test.tsx` renders the **real** UI components against a **real** backend —
`fetch` is not stubbed. It is opt-in so the default suite stays green offline:

```bash
# 1. start the backend as above (it must be on http://localhost:8080)
# 2. run the live tests
QA_LIVE=1 npx vitest run src/app/live.integration.test.tsx
```

These are the tests that catch contract drift between the client and the server, which mocked
fixtures cannot: they caught the campaign list returning a bare JSON array where the client
expected a page envelope, which had blanked every campaign screen in production.

### The campaign list response shape

`GET /api/{admin,creator}/campaigns` and `GET /api/restaurants/campaigns` paginate with
`page`/`size` but **serialise the page as a bare JSON array**, not Spring's
`{content, totalElements}` envelope. `fetchScoped` in `src/services/backend/campaigns.ts`
normalises either shape, so a future backend change to a real envelope is also accepted. The
bookings and notifications endpoints *do* return envelopes (`{items, total, …}`); the two are
deliberately normalised in their own modules.

## Known limitations

Stated plainly so nothing here is mistaken for finished work:

* **Booking creation does not require an assignment.** A creator may request a booking at any
  existing restaurant; the wizard and the review form *are* gated on an active assignment, and the
  backend enforces review eligibility. Adding the same check to booking creation is a backend
  change, not a UI one.
* **Campaign search filters the current page**, not the whole table, because the backend's list
  endpoints paginate and expose no title-search parameter.
* **Report rows are capped** at 200 campaigns per response and at a 366-day range, so a very large
  tenant needs paged access rather than one unbounded response.
* Payment processing is intentionally not integrated; `subscriptions.external_ref` is reserved for
  a future provider. Plan and status changes are administrator-driven records, never a simulated
  payment.
* **The demo store (`src/app/store.ts`) is still on disk** for the promo-code generator and its
  unit tests, but no screen imports it. It can be deleted once those tests move to the backend's
  own promo-code engine.