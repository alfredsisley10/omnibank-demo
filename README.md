# Omnibank

Omnibank is a synthetic enterprise-Java banking application used as an evaluation target for AI coding agents (the `ai-bench` harness). It deliberately looks and feels like a megabank monorepo — a Spring Boot monolith broken into ~650 Gradle modules, with a mix of richly-implemented flagship domains, thin API-and-stub skeletons, and large volumes of generated ballast that mimic product variants, delivery channels, legacy subsystems, and payments-network adapters.

It is not a real bank and is not suitable for production use. Balances, rates, account numbers, and regulatory logic are stylised approximations that exist to give an agent realistic-looking ground to walk on.

## Why this exists

Most real banks carry a monorepo that is too big to fit in a single LLM context window, full of long-dead code nobody has the courage to delete, and full of near-duplicate modules driven by regulatory geography, product proliferation, and channel fragmentation. Omnibank reproduces that shape at reduced size so agents can be measured against it reproducibly:

- Enough real code to reason about (ledger, accounts, lending, payments).
- Enough ballast that "read every file" is not a viable strategy.
- Enough dead code, near-duplicates, and misleading naming that agents must actually trace call sites rather than pattern-match.

## Stack

| Layer | Choice |
|---|---|
| Language | Java 17 (toolchain-enforced; Gradle 8.14.4 runs on JDK 17-25 — OpenJDK, Oracle JDK, Temurin, Corretto, Zulu all supported) |
| Framework | Spring Boot 3.3.4 |
| Build | Gradle (Kotlin DSL), with `org.gradle.parallel` + configuration cache |
| Persistence (SQL) | Spring Data JPA; H2 (dev default), PostgreSQL (profile `postgres`) |
| Persistence (NoSQL) | Spring Data MongoDB via `shared-nosql`; in-memory fallback by default, real Mongo with profile `mongo` (Testcontainers in tests) |
| Streaming | Apache Kafka via `shared-kafka` and `spring-kafka`; in-memory bus by default, real broker with profile `kafka` |
| Migrations | Flyway, per-module script locations |
| Testing | JUnit 5, AssertJ, Spring Boot Test, Mockito, Testcontainers BOM (Kafka + MongoDB) |
| Instrumentation | [AppMap](https://appmap.io/) Gradle plugin + interactive recording UI under `/appmap-ui/` |

## Repository layout

```
omnibank/
├── app-bootstrap/                 Spring Boot entry point; wires every module
├── customer-portal-api/           Customer-facing REST surface
├── admin-console-api/             Internal ops REST surface
│
├── shared-domain/                 Cross-module domain types
├── shared-persistence/            JPA base classes, repositories, converters
├── shared-security/               Auth + RBAC primitives
├── shared-messaging/              Async messaging abstractions
├── shared-testing/                Test fixtures and helpers
│
├── ledger-core/                   Double-entry ledger, postings, trial balance
├── accounts-consumer/             Retail account lifecycle, holds, interest, fees
├── accounts-corporate/            Corporate account skeleton
├── lending-consumer/              Consumer lending skeleton
├── lending-corporate/             Corporate credit engine
├── payments-hub/                  ACH, wire, RTP, FedNow routing
├── cards/                         Card auth, tokenization, disputes, rewards
├── treasury/                      Liquidity / FX skeleton
├── fraud-detection/               Rules, velocity, device trust skeleton
├── compliance/                    KYC, OFAC/sanctions, AML, CTR
├── risk-engine/                   VaR, stress, exposure, counterparty risk
├── reg-reporting/                 Call reports, HMDA, CCAR, LCR
├── statements/                    Rendering, archive, delivery
├── notifications/                 Email / SMS / push dispatch
├── audit-log/                     Append-only audit trail
├── batch-processing/              EOD close, reconciliation, job orchestration
├── integration-gateway/           SFTP, MQ, mainframe, SOAP/REST facades
│
├── shared-kafka/                  Kafka producer/consumer abstractions, AppMap-correlated trace headers
├── shared-nosql/                  MongoDB document store + in-memory fallback
├── appmap-recording-ui/           Interactive AppMap Recording Studio (REST + HTML/JS UI)
├── transaction-stream/            Cross-database streaming-transaction orchestrator (SQL + Mongo + Kafka)
│
├── generated/                     51 product-variant modules  (codegen)
├── generated-brands/              89 brand-scoped product forks (codegen)
├── generated-channels/            120 channel adapters — web/mobile/ATM/IVR/... (codegen)
├── generated-regional/            250 state-by-state regional forks (codegen)
├── generated-nacha/               24 NACHA SEC-code handlers (codegen)
├── generated-swift/               46 SWIFT MT message handlers (codegen)
└── generated-legacy/              51 "retired" subsystems kept in-tree as ballast (codegen)
```

Totals: ~30 hand-authored modules + ~630 generated modules, all wired into a single `settings.gradle.kts`.

### Module conventions

- `api/` — public types other modules may depend on (services, DTOs, events).
- `internal/` — implementation detail; do not reach in from outside the module.
- Flyway scripts live under `src/main/resources/db/migration/<module_name>/`.
- AppMap is configured via the root `appmap.yml`; `shared-testing` and `*.api.dto` packages are excluded from capture.

### Generated modules

Anything under `generated*/` is produced by a code generator under `tooling/codegen/` and is **not hand-edited**. Each directory has its own README describing the driver CSV and the generator class (for example `generated/README.md`, `generated-legacy/README.md`). If you need to change them, change the generator input and re-run, do not patch outputs.

`generated-legacy/` is intentionally deceptive ballast: the modules compile, are wired into Gradle, carry plausible `@Deprecated` annotations and stale ticket references, and look like the kind of retired subsystem a megabank would still be unable to delete years after replacing it. Tracing call sites is the only reliable way to know a legacy-shadow module is dead.

## Getting started

### Prerequisites

- JDK 17 through 25 (the Gradle toolchain pins source/target to 17 for cross-compat; 17 / 21 / 25 all work — including Oracle JDK 25. Gradle will auto-download a JDK 17 via the Foojay resolver if the default on `PATH` is a different major version).
- No external services required for the default dev profile — H2 runs in-process.

### Build everything

```bash
./gradlew build
```

### Run the banking app

```bash
./gradlew :app-bootstrap:bootRun
```

Then visit:

- `http://localhost:8080/` — HTML landing page with auth status, balance lookup, endpoint catalog, and curl recipes. Sending `Accept: application/json` returns a machine-readable service-info document instead.
- `http://localhost:8080/actuator/health` — public health probe.
- `http://localhost:8080/api/v1/accounts/{accountNumber}/balance` — authenticated balance lookup.
- `http://localhost:8080/api/v1/payments` — authenticated payments API.

### Authentication

The demo uses Spring Security's default in-memory user. The generated password is printed to the app log at startup:

```
Using generated security password: <password>
```

Use HTTP Basic with username `user` and that password, or drive the app via the `ai-bench` WebUI's auto-login bridge (`/_demo/autologin`). See `app-bootstrap/src/main/java/com/omnibank/DemoSecurityConfig.java` for the filter chain; everything under `/api/**` requires auth, while `/`, `/actuator/**`, `/error`, `/favicon.ico`, `/_demo/autologin`, and `/_appmap/**` are public.

### Switch to PostgreSQL

```bash
SPRING_PROFILES_ACTIVE=postgres ./gradlew :app-bootstrap:bootRun
```

Expects a reachable `jdbc:postgresql://localhost:5432/omnibank` with user/password `omnibank`/`omnibank`. See `app-bootstrap/src/main/resources/application.yaml` to adjust.

### Tests

```bash
./gradlew test                                    # whole tree
./gradlew :accounts-consumer:test                 # one module
./gradlew :app-bootstrap:test --tests '*Flow*'    # end-to-end flow tests
```

Flow tests under `app-bootstrap/src/test/java/com/omnibank/functional/` exercise cross-module scenarios (customer onboarding, payment processing, wire lifecycle, card spending).

The test suite is split across four levels:

| Level | Where | What it covers |
|---|---|---|
| **Unit** | `*Test.java` colocated with the production code | Individual classes — domain types, value objects, services with mocked collaborators |
| **Slice / Controller** | `*ControllerTest.java`, `*ControllerIntegrationTest.java` | MockMvc-driven tests against a single controller + its advice |
| **Functional** | `app-bootstrap/src/test/java/com/omnibank/functional/` | End-to-end Spring Boot scenarios that traverse multiple modules |
| **Performance** | `*PerformanceTest.java` | Soft throughput budgets, eg. 500 streaming-transaction publishes in <10s |
| **Integration (docker-tagged)** | `@Tag("docker")` classes | Real Kafka / MongoDB via Testcontainers — skipped unless Docker is available |

The Testcontainers tests (`TracedKafkaIntegrationTest`, `MongoDocumentStoreIntegrationTest`) carry `@Tag("docker")` so the default test run stays hermetic. To include them:

```bash
./gradlew test -PincludeDocker=true                # opt-in flag (when wired in CI)
```

### AppMap tracing

AppMap instrumentation is off by default. Turn it on for a run:

```bash
./gradlew -Pappmap_enabled=true :accounts-consumer:test
```

Captured traces land under `tmp/appmap/` and are ignored by git. `app-bootstrap` forwards `-PjvmArgs="..."` onto `bootRun` so the WebUI's "Start banking app with AppMap agent" button can attach the agent at runtime.

### AppMap Recording Studio (interactive UI)

The `appmap-recording-ui` module ships an interactive web app users can use to **create their own AppMaps** by clicking through the demo:

- HTML: `http://localhost:8080/appmap-ui/index.html`
- REST control plane: `/api/v1/appmap/recordings/**` (auth required)
- Pre-canned playbooks: `/api/v1/appmap/playbooks/**`

The studio supports the full lifecycle:

1. **Start** a recording with a label + description.
2. **Trigger** a built-in playbook (open account, submit ACH, balance lookup) or annotate the recording with a free-form action note.
3. **Save** the recording — the appmap JSON lands under `tmp/appmap/interactive/` and is downloadable straight from the UI.
4. **Cancel** mid-recording to discard, or **stop** without saving to inspect captured state first.

The studio does NOT require the AppMap Java agent to be attached. When the agent is missing it falls back to "synthetic" mode (configurable via `omnibank.appmap.synthetic-recording`) — the action narrative is still captured and a placeholder JSON is written so test fixtures can drive the full lifecycle without bringing the agent into the JVM. To force "agent or nothing" semantics, activate the `appmap-strict` profile.

Profiles that affect the studio:

| Profile / property | Effect |
|---|---|
| (default) | Synthetic mode on so the UI works without the agent |
| `appmap-strict` | Disable synthetic mode — recordings only work with the agent attached |
| `omnibank.appmap.archive-dir` | Override the on-disk archive root (default `tmp/appmap/interactive`) |

### Kafka integration

`shared-kafka` provides Kafka topic constants, a `TracedKafkaPublisher` that stamps every record with an AppMap-correlated trace context, and a `TracedConsumerInterceptor` that reconstructs the trace on the consumer side. Topics are pre-declared under `KafkaTopics`:

- `omnibank.payment.events`, `omnibank.ledger.events`, `omnibank.account.events`, `omnibank.fraud.signals`, `omnibank.compliance.alerts`
- `omnibank.payment.command.submit`, `omnibank.ledger.command.post`
- `omnibank.audit.trail`, `omnibank.appmap.spans`

A real broker is OFF by default — the in-memory bus (`InMemoryKafkaBus`) handles producer/consumer fan-out so the demo runs end-to-end without external infrastructure. Switch on via:

```bash
SPRING_PROFILES_ACTIVE=kafka ./gradlew :app-bootstrap:bootRun \
  -Domnibank.kafka.bootstrap-servers=broker:9092
```

`AppMapSpanRecorder` keeps a ring of recently observed produce/consume spans which the streaming-transaction controller exposes at `/api/v1/txstream/spans` for the recording UI to render live.

### NoSQL (MongoDB) integration

`shared-nosql` defines a `DocumentStore` interface with two implementations:

- `InMemoryDocumentStore` — default; deterministic, fast, no external dependencies.
- `MongoDocumentStore` — Spring Data Mongo backed; activated via the `mongo` profile + `omnibank.nosql.mongo.enabled=true`.

```bash
SPRING_PROFILES_ACTIVE=mongo ./gradlew :app-bootstrap:bootRun \
  -Dspring.data.mongodb.uri=mongodb://localhost:27017/omnibank
```

### Streaming transactions across SQL + NoSQL + Kafka

The `transaction-stream` module wires the three persistence/streaming layers into a single business operation. Each `POST /api/v1/txstream/publish` call:

1. Inserts a row into the `txstream_transactions` SQL table (system of record).
2. Upserts a denormalised projection into the Mongo `txstream_transactions` collection.
3. Emits a record on the `omnibank.payment.events` Kafka topic, which a registered consumer projects into the `txstream_consumer_view` Mongo collection.

Every leg's outcome is reported individually so AppMap traces (and the recording UI's "captured timeline") show which subsystem dominated latency or failed. Failures in legs 2 and 3 surface as warnings without rolling back leg 1 — matching the "system of record commits first" convention used in the existing payments hub.

This is the recommended scenario to record an AppMap against:

```bash
# 1. Bootstrap the studio (synthetic mode is fine)
./gradlew :app-bootstrap:bootRun

# 2. Open http://localhost:8080/appmap-ui/index.html
# 3. Click "Start recording", run the "Submit ACH payment" playbook a couple of times
# 4. Click "Save appmap" — the JSON lands under tmp/appmap/interactive/
```

## Working in this repo

- Start from `app-bootstrap` to see how the monolith is wired.
- Real business logic lives in `ledger-core`, `accounts-consumer`, `lending-corporate`, and `payments-hub` — these are the "flagship" modules. Other business modules publish APIs and stub implementations.
- The cross-cutting infrastructure modules (`shared-kafka`, `shared-nosql`, `appmap-recording-ui`, `transaction-stream`) are all "rich impl" — they have substantive code, full unit tests, and a Spring auto-configuration that consents to be turned off via property flags. They are the recommended targets when an evaluation needs an AppMap that crosses persistence boundaries.
- Before modifying code under `generated*/`, confirm whether it's actually on a live call path — much of it is not.
- Module boundaries are enforced only by package conventions, not by the build. Please keep `internal/` types out of other modules' imports.

## End-to-end recording walkthrough

The fastest way to produce an interesting AppMap end-to-end:

1. `./gradlew :app-bootstrap:bootRun` — boots with the in-memory bus and document store.
2. Open `http://localhost:8080/appmap-ui/index.html`, label a recording, click **Start recording**.
3. Click **Run** on the *Submit ACH payment* playbook — exercises the cross-module call chain.
4. Hit `POST /api/v1/txstream/publish` (curl or the recording UI's custom-action note + a separate curl) — that one call writes to the SQL ledger, projects to Mongo, fans out to Kafka, and the registered consumer projects the consumer view back into Mongo.
5. Click **Save appmap** — the JSON lands at `tmp/appmap/interactive/<recording-id>.appmap.json` and a download button appears.

## License

Not licensed for external reuse — synthetic benchmark target only.
