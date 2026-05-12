# AstraSecure — Backend Stack

This document records the chosen technology stack for the AstraSecure
**REST API server** (the new server to be built in Phase 1+) and how it
integrates with the existing Signal relay.

The Signal relay is third-party-derived and is treated here as a fixed
component — its language and framework are not in scope for this decision.

---

## 0. Actual vs. planned stack

**The Phase 0 architecture docs specified Ktor + Kotlin. The actual server
that exists is Node.js/Express.** The REST API has been built on top of the
existing Node.js server rather than rewriting it. This section documents the
real choices. References to Ktor/Kotlin below (§3–§5) are retained as
historical context; the live system is Node.js throughout.

## 1. Decision summary (actual)

| Layer | Choice | Why |
|---|---|---|
| Language | **Node.js 20 (JavaScript)** | Existing Signal relay is already Node.js; adding the REST API layer to the same process avoids running two servers |
| Web framework | **Express 4.x** | Already in `package.json`; fast; team has existing code to build on |
| Persistence | **PostgreSQL 16** + **pg** (node-postgres) | Existing schema + pool; parameterised queries prevent SQL injection |
| Schema management | **schema.sql applied on every boot** (`IF NOT EXISTS` + `ALTER TABLE IF NOT EXISTS`) | Idempotent; no migration runner needed at this scale |
| JSON | Native `JSON.parse` / `JSON.stringify` | No additional library |
| JWT | **jsonwebtoken** (npm) — **HS256** with `JWT_SECRET` env var | Already implemented for the Signal relay; single-server means no need for asymmetric RS256 |
| Logging | `console.log` / `console.error` | Sufficient for capstone; captured by Docker stdout |
| Build | **npm** | Existing `package.json` |
| Container | **Docker** (node:20-alpine) | Existing `Dockerfile` |
| Reverse proxy / TLS | **Caddy 2.x** (recommended) or the built-in TLS option (`TLS_CERT`/`TLS_KEY` env vars) | Existing TLS option already in server |

---

## 2. Repository layout

The REST API lives in a new top-level module of this repository, **not** a
separate repo. This keeps shared DTOs, the contract spec, and CI all in one
place for the capstone deliverable.

```
Capstone/
├── app/                     # existing Android client (unchanged here)
├── server/                  # NEW — REST API server
│   ├── build.gradle.kts
│   ├── src/main/kotlin/com/explo/astrasecure/server/
│   │   ├── App.kt                         # Ktor entry point
│   │   ├── config/                        # ApplicationConfig, env loading
│   │   ├── auth/                          # JWT validator, JWKS client, principal
│   │   ├── routes/
│   │   │   ├── SyncRoute.kt
│   │   │   ├── UserRoute.kt
│   │   │   ├── MissionRoute.kt
│   │   │   ├── ChannelRoute.kt
│   │   │   ├── ClearanceRoute.kt
│   │   │   ├── SchemaRoute.kt
│   │   │   └── AuditRoute.kt
│   │   ├── domain/                        # service-layer (transactional)
│   │   ├── db/
│   │   │   ├── Tables.kt                  # Exposed table definitions
│   │   │   ├── Database.kt                # connection pool, init
│   │   │   └── migrations/                # Flyway SQL files
│   │   ├── push/                          # WS push to Signal relay (sync_invalidated)
│   │   └── audit/                         # AuditLog service
│   ├── src/main/resources/
│   │   ├── application.conf               # Ktor HOCON config
│   │   └── db/migration/                  # Flyway migrations
│   └── src/test/                          # integration + service tests
├── shared/                  # NEW — DTOs shared between app and server
│   └── src/commonMain/kotlin/com/explo/astrasecure/shared/
│       └── dto/                           # request/response classes
├── docs/architecture/       # this folder
└── docker/
    ├── server.Dockerfile
    └── docker-compose.yml                  # postgres + server + caddy for local dev
```

The `shared` Gradle module is a Kotlin Multiplatform module so the Android
`app/` can also depend on it, removing duplicate DTO definitions between
client and server.

---

## 3. Why Ktor (not Spring Boot)

Both were considered. Ktor wins for this project because:

| Criterion | Ktor | Spring Boot |
|---|---|---|
| Cold-start time | ~1 s | ~5–8 s |
| Dependency surface | Small, opt-in | Large, transitive |
| Coroutine-native | Yes | Reactor-based; coroutine bridge needed |
| Reflection at runtime | Minimal | Heavy |
| Kotlin-first DTO mapping | Native (kotlinx.serialization) | Jackson + Kotlin module |
| Deploy size | ~30 MB JAR | ~80 MB JAR |
| Fits a single-developer mental model | Yes | Costs initial config burden |
| Capstone scoping (no need for ecosystem features) | Excellent | Overkill |

Spring Boot would be the right call if the team were targeting enterprise
production with a long maintenance horizon and many devs. The capstone scope
favours Ktor's simplicity.

---

## 4. Why Exposed + Flyway (not JPA / Hibernate, not jOOQ)

- **Exposed** is the canonical Kotlin-native ORM. Its DSL is type-safe and
  composes naturally with coroutines. The DAO layer covers the simple CRUD
  cases; the DSL covers the complex `/sync` join.
- **JPA / Hibernate** carries lazy-loading and N+1 footguns that are
  out-of-step with Kotlin idioms; not chosen.
- **jOOQ** would be excellent if the team prioritised SQL fidelity over
  Kotlin ergonomics; rejected because Exposed is enough.
- **Flyway** runs raw SQL migrations — visible to anyone reading the repo,
  no DSL translation layer, easy to copy-paste into psql for debugging.

---

## 5. Configuration

All runtime configuration via **environment variables**, validated at boot
through a single `Config` object. The `application.conf` (HOCON) reads from
env with defaults for local dev only — production deployments must set
every variable explicitly.

Required env vars:

| Var | Example | Purpose |
|---|---|---|
| `ASTRA_HTTP_PORT` | `8080` | Ktor bind port |
| `ASTRA_HTTP_HOST` | `0.0.0.0` | Bind host (use `127.0.0.1` if behind reverse proxy on same box) |
| `ASTRA_DB_URL` | `jdbc:postgresql://localhost:5432/astra` | JDBC URL |
| `ASTRA_DB_USER` | `astra_app` | DB role (least privilege — see §8) |
| `ASTRA_DB_PASSWORD` | (secret) | DB role password |
| `ASTRA_JWKS_URL` | `https://signal.astrasecure.app/.well-known/jwks.json` | Public keys for JWT validation |
| `ASTRA_JWT_ISSUER` | `https://signal.astrasecure.app` | Expected `iss` claim |
| `ASTRA_RELAY_BASE_URL` | `https://signal.astrasecure.app` | For server-to-server `sync_invalidated` push and channel-membership cleanup |
| `ASTRA_RELAY_INTERNAL_TOKEN` | (secret) | Pre-shared token for the relay-to-API admin channel |
| `ASTRA_LOG_LEVEL` | `INFO` | Logback root level |
| `ASTRA_RATE_LIMIT_REDIS_URL` | `redis://localhost:6379` | Optional — enables distributed rate limiting (Phase 7+) |

Secrets must **never** be committed. `.env.example` ships with placeholder
values; `.env` is in `.gitignore`. In production, secrets come from the
deployment platform's secret manager (e.g. Docker secrets, systemd
`LoadCredential=`, or a vault).

---

## 6. Database setup

### Connection pool
**HikariCP** (default with Exposed). Pool size: `min=4, max=20`. The
expected concurrent operator count for a capstone demo is trivial; these
sizes are conservative defaults that avoid resource starvation under
unexpected load.

### Migrations
Flyway runs on every server boot **before** the HTTP listener starts
accepting connections. A migration failure aborts boot. Migrations live in
`server/src/main/resources/db/migration/V{n}__{description}.sql`:

```
V001__initial_schema.sql           # users, schema_*, missions, channels,
                                    # mission_participants, clearance_assignments,
                                    # audit_events, sync_versions, jwt_revoked
V002__seed_system_records.sql      # the seven system ranks/categories/types
                                    # — the on-server replacement for SeedData.kt
V003__indexes.sql                  # secondary indexes once query patterns settle
```

### Index plan (V003)
- `users(callsign)` UNIQUE
- `missions(created_by)` for "missions I created" queries
- `mission_participants(user_id)` for `/sync` scoping
- `channels(mission_id)` already implied via FK; explicit for query planner
- `clearance_assignments(mission_id)` for "every chief on this mission" lookup
- `audit_events(user_id, occurred_at DESC)` for paginated audit reads
- `sync_versions(user_id)` PK already covers it

---

## 7. Connection between REST API and Signal relay

The REST API needs two things from the Signal relay:

1. **JWT validation** — fetch JWKS at boot, refresh on `kid` cache miss.
   Standard public-key flow, no special access required.
2. **Server-to-server admin operations:**
   - `POST /v1/admin/sync_invalidated` — push a `sync_invalidated` WS frame
     to a specific user (when their version bumps). Authenticated with
     `ASTRA_RELAY_INTERNAL_TOKEN` in an `X-Internal-Token` header.
   - `DELETE /v1/admin/channels/{channelId}/members` — clear a channel's
     membership when the REST API deletes the channel.

These admin endpoints **do not exist on the relay yet**. They are part of
the Phase 1 scope and must be added to the Signal relay codebase. They
must be reachable only over the internal network or via mutual TLS — never
exposed to client devices.

---

## 8. Database role separation

Two Postgres roles:

| Role | Privileges | Used by |
|---|---|---|
| `astra_app` | `CONNECT`, table-level CRUD on app tables | The Ktor server process |
| `astra_migrator` | `CONNECT`, `CREATE`, `ALTER`, all the above | Flyway only, run in a separate boot phase |

The migration step uses the elevated role; once migrations succeed, the
application connection pool is created with the lower-privilege role. This
ensures a runtime SQL injection (if one ever slipped past parameterised
queries) cannot ALTER schema.

---

## 9. Local development

A `docker-compose.yml` brings up the full stack on a developer's laptop:

```yaml
services:
  postgres:
    image: postgres:16
    environment:
      POSTGRES_USER: astra_migrator
      POSTGRES_PASSWORD: localdev
      POSTGRES_DB: astra
    ports: ["5432:5432"]
    volumes: [astra-pgdata:/var/lib/postgresql/data]

  server:
    build: { context: ., dockerfile: docker/server.Dockerfile }
    environment:
      ASTRA_DB_URL: jdbc:postgresql://postgres:5432/astra
      ASTRA_DB_USER: astra_app
      ASTRA_DB_PASSWORD: localdev
      ASTRA_JWKS_URL: http://relay-mock:9090/.well-known/jwks.json
      ASTRA_JWT_ISSUER: http://relay-mock:9090
      ASTRA_RELAY_BASE_URL: http://relay-mock:9090
    depends_on: [postgres, relay-mock]
    ports: ["8080:8080"]

  relay-mock:
    # A small Ktor app that mimics the Signal relay's JWKS endpoint
    # and accepts the s2s admin endpoints. Used for local dev only.
    build: { context: ./tools/relay-mock }
    ports: ["9090:9090"]

volumes:
  astra-pgdata:
```

The `relay-mock` is a tiny Ktor app that issues test JWTs signed with a
locally-generated keypair. It is **not** the production Signal relay; it
exists so the team can develop the REST API without standing up the full
Signal codebase.

---

## 10. Production deployment shape

For the capstone demo, "production" means a single VM (e.g. a 2 vCPU /
4 GB cloud instance) running:

```
            ┌─────────────────────────────────────────┐
            │            Single VM (Linux)            │
            │                                         │
            │   ┌────────┐    ┌────────────────────┐  │
   :443 ────┼──▶│ Caddy  │───▶│ Ktor (REST API)    │  │
            │   │ (TLS)  │    │ :8080              │  │
            │   └────────┘    └────────┬───────────┘  │
            │                          │              │
            │                          ▼              │
            │                  ┌────────────────┐     │
            │                  │ PostgreSQL 16  │     │
            │                  │ (localhost)    │     │
            │                  └────────────────┘     │
            └─────────────────────────────────────────┘
                          (Signal relay on a separate VM
                           with its own Caddy + TLS)
```

Caddy handles ACME / Let's Encrypt automatically. The Ktor process binds to
`127.0.0.1:8080`; only Caddy is reachable from the internet on `:443`.

systemd unit files manage process lifecycle and supply env vars via
`EnvironmentFile=/etc/astra/server.env` and credential files via
`LoadCredential=`. No secrets in the unit file itself.

This is **deliberately simple**. A future team scaling beyond a capstone
deployment would put the API behind a load balancer, run Postgres on a
managed service (e.g. RDS), and ship logs off-box. None of that complexity
is justified for the capstone.

---

## 11. Observability

### Logs
JSON to stdout via Logback. Captured by the systemd journal and can be
shipped to any aggregator. Every request gets a correlation ID
(`X-Request-Id` from header or generated on entry); the ID is attached to
every log line via MDC and is also returned in the response header so a
client error report can be cross-referenced.

### Metrics
**Micrometer** with Prometheus registry, exposed at `GET /metrics` on a
separate port (`:8081`) — internal-network only. Default JVM metrics +
custom counters per endpoint (request count, error count, latency
histogram).

### Tracing
**Out of scope for the capstone.** A distributed tracing library would be
warranted only if/when the system grows to multiple services.

---

## 12. CI

A single GitHub Actions workflow:

```yaml
jobs:
  build:
    steps:
      - checkout
      - setup-java (Temurin 21)
      - gradle :server:test
      - gradle :server:installDist
      - gradle :app:assembleDebug

  docker:
    needs: build
    if: github.ref == 'refs/heads/master'
    steps:
      - build server image
      - push to a registry (TBD — GitHub Container Registry by default)
```

Tests must run against a real Postgres (Docker-spun, not in-memory) — the
unit/integration distinction is intentionally blurred to catch SQL bugs
early. Test data fixtures live in `server/src/test/resources/fixtures/`.

---

## 13. Versioning and release

The server and Android client share a top-level Gradle `version` for the
capstone duration; bumps are manual. There is **no API versioning beyond
the `/api/` v1 prefix** for Phase 0–7. If the team needs a breaking change
later, mount a parallel `/api/v2/` and keep `/api/` running for a release.

---

## 14. Open questions

- **Hosting target for the capstone demo** — university VM, free-tier cloud,
  team-member's home server? Decide before Phase 1 so TLS / DNS can be set
  up. Recommendation: a free-tier cloud VM (Oracle Free Tier or similar)
  for predictable demos.
- **Domain name** — `astrasecure.app` is a placeholder throughout this
  documentation. The team should register the actual domain before TLS is
  configured.
- **Shared DTO module** — current plan uses Kotlin Multiplatform. If KMP
  setup proves difficult, fall back to publishing the DTOs as a JVM-only
  library and copy-pasting the small handful of classes to the Android
  module. Decide at the start of Phase 1.
- **Signal relay coordination** — the Phase 1 scope assumes the team has
  source access to the Signal relay to add the `/v1/admin/*` endpoints.
  If not, those endpoints must instead be implemented as a thin sidecar
  service co-located with the relay. Confirm relay access before Phase 1.
