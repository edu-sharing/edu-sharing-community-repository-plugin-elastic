# CLAUDE.md — deploy module

Packaging & deployment artifacts: Docker images, Docker Compose stacks, and Helm charts. Pure
build/ops module — no application Java code. See the root `CLAUDE.md` for project-wide context and
`tracker/CLAUDE.md` for the service that gets packaged here.

Shared properties (`deploy/pom.xml`): `docker.repository=community`, image/chart prefix
`edu_sharing-community-repository-plugin-elastic`. Build/test with the `dev` profile (`-Pdev`).

## Submodule map

```
deploy/docker/
├── build/        Docker image builds (io.fabric8 docker-maven-plugin)
│   ├── tracker/  → image  community/...-deploy-docker-build-tracker   (the tracker service)
│   └── service/  → image  community/...-deploy-docker-build-service
├── compose/      Docker Compose stack fragments (common/dev/debug/productive/remote)
└── helm/         Helm charts (io.kokuwa.maven helm-maven-plugin)
    ├── tracker/  StatefulSet + Job chart for the tracker
    └── index/    StatefulSet chart for the Elasticsearch index service
```

Shared base images & versions are centralized in `deploy/docker/pom.xml` — notably
`elastic.stack.version` (8.18.1, must match the client version in `tracker/`) and
`docker.from.openjdk.17.liberica.debian` (the tracker base image).

## docker/build/tracker (focus)

Builds the runtime Docker image for the tracker Spring Boot service.

- **`pom.xml`** — two key steps:
  1. `maven-dependency-plugin:copy-dependencies` (phase `package`) pulls the
     `edu_sharing-community-repository-plugin-elastic-tracker` jar into `target/build/artifacts`.
  2. `docker-maven-plugin` builds the image (phase `install`) and pushes it (phase `deploy`).
     → image `${docker.repository}/${docker.prefix}-deploy-docker-build-tracker:${docker.tag}`.
  - Therefore the tracker jar must be built first; running from repo root with `-am` handles this:
    `mvn -s .mvn/settings.xml -Pdev -pl deploy/docker/build/tracker -am install`.
- **`src/main/build/Dockerfile`** — based on Liberica OpenJDK 17 (Debian). Installs `curl`/`wget`/
  `wait-for-it`, runs as non-root user `worker` under `/opt/alfresco`, copies the tracker jar,
  bundles async-profiler, exposes **8080** (service) and **8081** (management/metrics).
  Note: Maven resource filtering (`build/pom.xml`) substitutes `${...}` placeholders (e.g. the jar
  version, git labels) at build time — the `${...}` tokens are intentional, not literal.
- **`src/main/build/assets/entrypoint.sh`** — runtime bootstrap. Waits for the ES index and the
  repository service to be healthy, then **writes/patches `application.properties` from env vars**
  (`REPOSITORY_SEARCH_ELASTIC_INDEX_HOST/PORT`, `REPOSITORY_SERVICE_HOST/PORT`,
  `REPOSITORY_SERVICE_ADMIN_PASS`, `*_SHARDS/_REPLICAS`, bind/port for server & management) before
  `exec java -jar`. This is where container config maps onto the tracker's Spring properties.
- **`src/main/build/assets/profiler/profile.sh`** — async-profiler helper inside the image, usage
  `profile.sh <duration-seconds> [event]`. `event` defaults to `itimer` (CPU flamegraph); pass `alloc`
  for an allocation flamegraph (`--total`, bytes per stack) when chasing a heap leak — `itimer` alone
  never records allocation/GC/heap events.

## Other submodules (index)

- **docker/build/service/** — companion service image; mainly source-jar packaging plus its own
  `entrypoint.sh` under `src/main/build/plugin-elastic/`.
- **docker/compose/** — Compose YAML fragments under `src/main/compose/` for the profiles
  common / dev / debug / productive / remote; combine to run the stack locally.
- **docker/helm/tracker/** & **docker/helm/index/** — Helm charts (`src/main/chart/`) packaged via
  `helm-maven-plugin` (init → dependency-build → lint → package → upload across Maven phases) and
  assembled into a `tar.gz`. Edit chart values/templates under `src/main/chart/`; `README.md` in each
  chart is validated in CI (`check-helmreadme`).
