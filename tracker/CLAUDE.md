# CLAUDE.md — tracker module

The tracker is a standalone Spring Boot 3.5.3 CLI service (Java 17) that indexes and synchronizes
Alfresco nodes into Elasticsearch. This file captures the non-obvious architecture; conceptual docs
(migrations, modes, indices) live in `README.md` in this directory.

## Entry point

- `Edu_SharingElasticsearchTracker.java` — `@SpringBootApplication`, `CommandLineRunner`, also
  `@EnableCaching` / `@EnableScheduling`. Imports `AutoConfigurationTracker`.
- `CLI.java` — picocli command handling (e.g. `--drop-index`).

## Package layout (`org.edu_sharing.elasticsearch.*`)

- `elasticsearch/config` — Spring config & ES client (`ElasticsearchConfig.java`), plus
  `config/mode` (mode-specific configurations) and `AutoConfigurationTracker.java` (default beans).
- `elasticsearch/core` — `WorkspaceService` (main search/index logic), `AdminService` (index
  create/delete + mappings), `StatusIndexService` (tracking-state persistence).
- `tracker/*` — the tracker framework plus ~28 concrete trackers (`main`, `acl`, `auth`, `content`,
  `collection`, `statistics`, `preview`, `relation`, `suggestions`, …).
- `edu_sharing/api` + `edu_sharing/client` — edu-sharing REST integration (uses generated client, see below).
- `alfresco/client` — Alfresco webscript/REST clients and model classes.
- `migrations`, `jobs`, `metric`, `cache` — index migrations, scheduled jobs, metrics context, caching.

## Tracker framework (core pattern)

`Tracker<STATUS>` / `TrackerConfig<PROPS,STATUS>` implemented via `AbstractTracker`. Trackers are
collected in `TrackerRegistry` and driven periodically by `TrackerScheduler`. Each tracker's progress
state is persisted into the `transactions` index through `StatusIndexService`.

## Bean naming convention (CRITICAL)

The configuration relies heavily on Spring's **qualifier** concept: beans of the same type are
resolved by name (bean name defaults to the `@Bean` factory method name; injection resolves by
member/field name when no `@Qualifier` is present). **Do not casually rename beans** — it silently
breaks configuration wiring. `AutoConfigurationTracker.java` defines default beans that are overridden
by `mode` configurations re-declaring the same bean name. (See `README.md` "Hints".)

## Modes & migrations

- The `mode` property selects behavior (e.g. `migration-only`). Mode configs live under
  `elasticsearch.config.mode` (e.g. `DefaultConfiguration`, `MigrationConfiguration`).
- Index migrations are declared as `MigrationInfo` beans (with `@Order`) in `migrations/Migrations.java`.
  These track **structural** index changes only, versioned manually. Full migration flow in `README.md`.

## Generated code (do not edit by hand)

edu-sharing REST clients are generated at build time by `openapi-generator-maven-plugin` into
`target/generated-sources/openapi`, package
`org.edu_sharing.generated.repository.backend.services.rest.client.*`. Regenerate via a Maven build
after API spec changes — never edit the generated sources.

## Elasticsearch client

`co.elastic.clients:elasticsearch-java` + `org.elasticsearch.client:elasticsearch-rest-client` (8.18.1),
configured in `ElasticsearchConfig.java`. Connection props (`elastic.host`, `elastic.port`, …) live in
`src/main/resources/application.properties`, with profile overrides
`application-k8s.properties` / `application-debug.properties` / `application-dev.properties`.

## Tests

JUnit 5 + Mockito + AssertJ (via `spring-boot-starter-test`). Tests under `src/test/java` and
`src/test/groovy`.

Always build/test with the `dev` profile (`-Pdev`).

- From repo root: `mvn -s .mvn/settings.xml -Pdev -pl tracker test`
- From this module: `mvn -s ../.mvn/settings.xml -Pdev test`
