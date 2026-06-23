# CLAUDE.md — config module

Default configuration artifacts for the elastic plugin, shipped as a jar and consumed by the host
edu-sharing repository / deployment. No Java code.

```
config/
└── defaults/   → edu_sharing-...-config-defaults (jar)
```

## Layout & build (`config/defaults`)

Two resource roots (`config/defaults/pom.xml`):

- `src/main/resources/plugin-elastic/` — copied **verbatim** (no filtering):
  - `edu-sharing.reference.conf` — HOCON reference config; defines `elasticsearch.servers` and
    `max_response_entity_size`. This is the reference that the repository merges into its effective config.
  - `alfresco-global.properties`, `caches.properties` — currently empty placeholders for repo-side overrides.
- `src/main/templates/plugin-elastic/` — **filtered** (Maven `${...}` substitution):
  - `version.json` — build/version metadata stamped from git + Maven properties at build time
    (`git.branch`, `git.commit.id`, parsed version components, etc.). The `${...}` tokens are intentional.

Editing rule of thumb: real default values go under `src/main/resources/`; anything that needs
git/build-time substitution goes under `src/main/templates/`.

`maven-source-plugin` also attaches a sources jar (phase `package`).
