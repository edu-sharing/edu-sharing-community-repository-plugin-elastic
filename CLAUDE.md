# CLAUDE.md

Guidance for working in this repository. Keep this file focused on project-specific,
non-obvious facts — it complements `README.md` and `tracker/README.md` rather than repeating them.

## What this is

`edu_sharing-community-repository-plugin-elastic` — an Elasticsearch/OpenSearch indexing &
synchronization plugin for the edu-sharing / Alfresco repository.

- Parent POM: `org.edu_sharing:edu_sharing-super-pom`
- groupId: `org.edu_sharing`, root packaging: `pom` (multi-module aggregator)

## Modules

Declared in `pom.xml`:

- **`Backend/`** — Alfresco AMP modules (`Backend/alfresco/module`, `Backend/services/module`).
  Alfresco dependencies are `provided`; these contain little/no own Java code (Alfresco-side hooks/assembly).
- **`tracker/`** — Spring Boot 3.5.3 standalone service, **Java 17**. This is where essentially all
  application code lives. See `tracker/CLAUDE.md`.
- **`config/`** — default configuration artifacts (`config/defaults`).
- **`deploy/`** — Docker Compose, Docker build, and Helm charts (`deploy/docker/helm/index`, `.../helm/tracker`).

## Versioning (non-obvious)

The root `<version>` is the literal `git`. Real versions come from the
**maven-git-versioning-extension** (`.mvn/extensions.xml`) and are derived from the branch name:
`maven/fixes/10.0` → `maven-fixes-10.0-SNAPSHOT`. Feature branches
(`maven/feature/<version>-Name`) map to the same `maven-fixes-<version>-SNAPSHOT`, optionally
prefixed via the `PROJECT_NAME` env var. Details in `README.md`.

## Build

`-s .mvn/settings.xml` is required — it points at the internal edu-sharing artifact repositories.
**Always build with the `dev` profile (`-Pdev`).**

- Full build: `mvn -s .mvn/settings.xml -Pdev install`
- Tracker only (with required modules): `mvn -s .mvn/settings.xml -Pdev -pl tracker -am install`
- CI (`.gitlab-ci.yml`) runs `deploy` with `-Dversioning.configFile=maven-git-versioning-extension-ci.xml`.

## Where to look next

- Tracker architecture, modes, migrations, generated clients: `tracker/CLAUDE.md`
- Migrations / modes / indices conceptual docs: `tracker/README.md`
- Git/branch versioning workflow: `README.md`
