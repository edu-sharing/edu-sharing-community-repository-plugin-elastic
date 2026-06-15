# CLAUDE.md — Backend module

Alfresco-side integration of the elastic plugin, packaged as **AMP** (Alfresco Module Package) files.
These modules hook the plugin into a running Alfresco/edu-sharing repository; they are *not* the
tracker service (that's `tracker/`, see `tracker/CLAUDE.md`).

Java 17 (`maven.compiler.*` set in `Backend/pom.xml`). License: LGPL v3.0+.

## Submodules

```
Backend/
├── alfresco/module/   → edu_sharing-...-backend-alfresco-module   (jar packaged as AMP)
└── services/module/   → edu_sharing-...-backend-services-module   (jar packaged as AMP)
```

`services/module` depends on `alfresco/module` (both `provided`), so alfresco builds first.

## How the AMP is built (non-obvious)

Each `*/module/pom.xml` has `packaging>jar` but assembles an **AMP** via `maven-assembly-plugin`
(`single` goal, phase `package`, descriptor `src/main/assembly/amp.xml`, `appendAssemblyId=false`).
`license-maven-plugin:add-third-party` (phase `generate-resources`) generates
`src/main/amp/licenses/THIRD-PARTY-*.txt` into the AMP. The root `pom.xml` pins
`maven-assembly-plugin` to **2.6** because 3.x is incompatible with the Alfresco SDK — don't bump it.

## What's inside the AMP (`src/main/amp/`)

- `module.properties` — AMP descriptor; `module.id`/`version` filled from Maven/git at build time.
- `file-mapping.properties` — `include.default=true` (standard AMP target mapping).
- `config/alfresco/module/<artifactId>/module-context.xml` (alfresco) and
  `src/main/resources/org/edu_sharing/spring/plugin-elastic-services.xml` (services) — Spring context
  contributions loaded by Alfresco. **Currently empty `<beans/>` placeholders** — this is where
  repository-side beans/behaviours would be registered when needed.

## Dependencies

Alfresco APIs (`alfresco-remote-api`, `alfresco-repository`) and the edu-sharing repository backend
artifacts are all `provided` — supplied by the host repository at runtime, not bundled in the AMP.
