## Parameters

### Global parameters

| Name                                    | Description                           | Value                  |
| --------------------------------------- | ------------------------------------- | ---------------------- |
| `global.annotations`                    | Define global annotations             | `{}`                   |
| `global.cluster.istio.enabled`          | Enable Istio Service mesh             | `false`                |
| `global.cluster.pdb.enabled`            | Enable PDB                            | `false`                |
| `global.debug`                          | Enable global debugging               | `false`                |
| `global.image.pullPolicy`               | Set global image pullPolicy           | `Always`               |
| `global.image.pullSecrets`              | Set global image pullSecrets          | `[]`                   |
| `global.image.registry`                 | Set global image container registry   | `${docker.registry}`   |
| `global.image.repository`               | Set global image container repository | `${docker.repository}` |
| `global.image.common`                   | Set global image container common     | `${docker.common}`     |
| `global.metrics.rules.enabled`          | Enable metrics rules                  | `false`                |
| `global.metrics.scrape.interval`        | Set prometheus scrape interval        | `60s`                  |
| `global.metrics.scrape.timeout`         | Set prometheus scrape timeout         | `60s`                  |
| `global.metrics.servicemonitor.enabled` | Enable metrics service monitor        | `false`                |
| `global.password`                       | Set global password                   | `""`                   |
| `global.security`                       | Set global custom security parameters | `{}`                   |

### Local parameters

| Name                                                    | Description                                                                                                | Value                                          |
| ------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------- | ---------------------------------------------- |
| `nameOverride`                                          | Override name                                                                                              | `edusharing-repository-search-elastic-tracker` |
| `image.name`                                            | Set image name                                                                                             | `${docker.prefix}-deploy-docker-build-tracker` |
| `image.tag`                                             | Set image tag                                                                                              | `${docker.tag}`                                |
| `replicaCount`                                          | Define amount of parallel replicas to run                                                                  | `1`                                            |
| `service.port.management`                               | Set port for service management                                                                            | `8081`                                         |
| `config.jvm.ram.minPercentage`                          | Set minimum memory in percentages                                                                          | `90.0`                                         |
| `config.jvm.ram.maxPercentage`                          | Set maximum memory in percentages                                                                          | `90.0`                                         |
| `config.jvm.malloc.arenaMax`                            | Cap glibc malloc arenas (MALLOC_ARENA_MAX) to bound native/off-heap RSS growth independent of the JVM heap | `2`                                            |
| `config.metrics.enabled`                                | Enable metrics                                                                                             | `true`                                         |
| `config.metrics.relabelings`                            | Define relabelings for metrics                                                                             | `[]`                                           |
| `config.metrics.rules.jvmHighGCRate.enabled`            | Enable metric rule jvmHighGCRate                                                                           | `true`                                         |
| `config.metrics.rules.jvmHighGCRate.config.max`         | Set metric rule jvmHighGCRate max gc rate                                                                  | `0.3`                                          |
| `config.metrics.rules.jvmHighGCRate.for`                | Set metric rule jvmHighGCRate wait interval                                                                | `5m`                                           |
| `config.metrics.rules.jvmHighGCRate.labels.severity`    | Set metric rule jvmHighGCRate severity level                                                               | `critical`                                     |
| `config.metrics.rules.trackingDelay.enabled`            | Enable metric rule trackingDelay                                                                           | `true`                                         |
| `config.metrics.rules.trackingDelay.config.max`         | Set metric rule trackingDelay max delay seconds                                                            | `300`                                          |
| `config.metrics.rules.trackingDelay.for`                | Set metric rule trackingDelay wait interval                                                                | `30m`                                          |
| `config.metrics.rules.trackingDelay.exclude`            | Trackers excluded from metric rule trackingDelay                                                           | `[]`                                           |
| `config.metrics.rules.trackingDelay.labels.severity`    | Set metric rule trackingDelay severity level                                                               | `critical`                                     |
| `config.metrics.rules.trackingProgress.enabled`         | Enable metric rule trackingProgress                                                                        | `true`                                         |
| `config.metrics.rules.trackingProgress.config.min`      | Set metric rule trackingProgress min progress rate                                                         | `95`                                           |
| `config.metrics.rules.trackingProgress.for`             | Set metric rule trackingProgress wait interval                                                             | `5m`                                           |
| `config.metrics.rules.trackingProgress.exclude`         | Trackers excluded from metric rule trackingProgress                                                        | `[]`                                           |
| `config.metrics.rules.trackingProgress.labels.severity` | Set metric rule trackingProgress severity level                                                            | `critical`                                     |
| `config.override`                                       | Configure overrides                                                                                        | `""`                                           |
| `config.repository.host`                                | Configure repository host                                                                                  | `edusharing-repository-service`                |
| `config.repository.port`                                | Configure repository port                                                                                  | `8080`                                         |
| `config.repository.password`                            | Configure repository password                                                                              | `""`                                           |
| `config.tracker.customScript`                           | Configure a custom script for the tracker                                                                  | `""`                                           |
| `config.tracker.customSynonyms`                         | Configure custom synonyms file for the tracker                                                             | `""`                                           |
| `config.search.elastic.host`                            | Set elasticsearch host                                                                                     | `edusharing-repository-search-elastic-index`   |
| `config.search.elastic.port`                            | Set elasticsearch port                                                                                     | `9200`                                         |
| `config.search.elastic.index.shards`                    | Set elasticsearch index shards                                                                             | `1`                                            |
| `config.search.elastic.index.replicas`                  | Set elasticsearch index replicas                                                                           | `2`                                            |
| `multistage.enabled`                                    | Enable multistage                                                                                          | `false`                                        |
| `multistage.stages`                                     | Set stages for multistage                                                                                  | `[]`                                           |
| `debug`                                                 | Enable debugging                                                                                           | `false`                                        |
| `nodeAffinity`                                          | Set node affinity                                                                                          | `{}`                                           |
| `tolerations`                                           | Set tolerations                                                                                            | `[]`                                           |
| `podAnnotations`                                        | Set custom pod annotations                                                                                 | `{}`                                           |
| `podSecurityContext.fsGroup`                            | Set fs group for access                                                                                    | `1000`                                         |
| `podSecurityContext.fsGroupChangePolicy`                | Set change policy for fs group                                                                             | `OnRootMismatch`                               |
| `securityContext.allowPrivilegeEscalation`              | Allow privilege escalation                                                                                 | `false`                                        |
| `securityContext.capabilities.drop`                     | Set drop capabilities                                                                                      | `["ALL"]`                                      |
| `securityContext.runAsUser`                             | Define user to run under                                                                                   | `1000`                                         |
| `terminationGracePeriod`                                | Define grace period for termination                                                                        | `120`                                          |
| `startupProbe.failureThreshold`                         | Failure threshold for startupProbe                                                                         | `30`                                           |
| `startupProbe.initialDelaySeconds`                      | Initial delay seconds for startupProbe                                                                     | `0`                                            |
| `startupProbe.periodSeconds`                            | Period seconds for startupProbe                                                                            | `20`                                           |
| `startupProbe.successThreshold`                         | Success threshold for startupProbe                                                                         | `1`                                            |
| `startupProbe.timeoutSeconds`                           | Timeout seconds for startupProbe                                                                           | `10`                                           |
| `livenessProbe.failureThreshold`                        | Failure threshold for livenessProbe                                                                        | `3`                                            |
| `livenessProbe.initialDelaySeconds`                     | Initial delay seconds for livenessProbe                                                                    | `30`                                           |
| `livenessProbe.periodSeconds`                           | Period seconds for livenessProbe                                                                           | `30`                                           |
| `livenessProbe.timeoutSeconds`                          | Timeout seconds for livenessProbe                                                                          | `10`                                           |
| `readinessProbe.failureThreshold`                       | Failure threshold for readinessProbe                                                                       | `1`                                            |
| `readinessProbe.initialDelaySeconds`                    | Initial delay seconds for readinessProbe                                                                   | `10`                                           |
| `readinessProbe.periodSeconds`                          | Period seconds for readinessProbe                                                                          | `10`                                           |
| `readinessProbe.successThreshold`                       | Set threshold for success on readiness probe                                                               | `1`                                            |
| `readinessProbe.timeoutSeconds`                         | Timeout seconds for readinessProbe                                                                         | `10`                                           |
| `resources.limits.cpu`                                  | Set CPU limit on resources                                                                                 | `500m`                                         |
| `resources.limits.memory`                               | Set memory limit on resources                                                                              | `2Gi`                                          |
| `resources.requests.cpu`                                | Set CPU for requests on resources                                                                          | `500m`                                         |
| `resources.requests.memory`                             | Set memory for requests on resources                                                                       | `2Gi`                                          |

### RAG / semantic search

| Name                                                        | Description                                                                        | Value                                           |
| ----------------------------------------------------------- | ---------------------------------------------------------------------------------- | ----------------------------------------------- |
| `rag.enabled`                                               | Enable the RAG chunk index and its embedding service                               | `false`                                         |
| `rag.profile.id`                                            | Embedding profile id; also the suffix of its index and its tracker cursor          | `bge-m3-v1`                                     |
| `rag.profile.model`                                         | Embedding model                                                                    | `BAAI/bge-m3`                                   |
| `rag.profile.dimensions`                                    | Vector length of the model - must match it exactly                                 | `1024`                                          |
| `rag.profile.maxChunksPerNode`                              | Ceiling per node; anything beyond is reported, not dropped silently                | `300`                                           |
| `rag.index.shards`                                          | Shards of the chunk index - it holds a multiple of the workspace index's documents | `3`                                             |
| `rag.index.replicas`                                        | Replicas of the chunk index                                                        | `1`                                             |
| `rag.embedding.replicaCount`                                | Replicas of the embedding service                                                  | `1`                                             |
| `rag.embedding.image.name`                                  | Embedding service image                                                            | `ghcr.io/huggingface/text-embeddings-inference` |
| `rag.embedding.image.tag`                                   | Image tag - use a CUDA variant on GPU nodes                                        | `cpu-1.8`                                       |
| `rag.embedding.maxBatchTokens`                              | Token budget per inference batch                                                   | `8192`                                          |
| `rag.embedding.probe.initialDelaySeconds`                   | Grace period while the model loads                                                 | `120`                                           |
| `rag.embedding.persistence.enabled`                         | Keep the downloaded model across restarts                                          | `false`                                         |
| `rag.embedding.persistence.spec.accessModes`                | Access modes of the model cache claim                                              | `["ReadWriteOnce"]`                             |
| `rag.embedding.persistence.spec.resources.requests.storage` | Size of the model cache claim                                                      | `20Gi`                                          |
| `rag.embedding.resources`                                   | Resources of the embedding service                                                 | `{}`                                            |
| `rag.embedding.nodeSelector`                                | Schedule the embedding service, e.g. onto GPU nodes                                | `{}`                                            |
| `rag.embedding.tolerations`                                 | Tolerations for the embedding service                                              | `[]`                                            |
| `rag.llm.enabled`                                           | Run a local model to formulate answers                                             | `false`                                         |
| `rag.llm.replicaCount`                                      | Replicas of the answer service                                                     | `1`                                             |
| `rag.llm.image.name`                                        | Answer service image                                                               | `ollama/ollama`                                 |
| `rag.llm.image.tag`                                         | Image tag                                                                          | `0.5.7`                                         |
| `rag.llm.model`                                             | Model to pull and serve                                                            | `qwen2.5:1.5b-instruct`                         |
| `rag.llm.contextLength`                                     | Token window the model is served with                                              | `8192`                                          |
| `rag.llm.keepAlive`                                         | How long a loaded model stays resident                                             | `24h`                                           |
| `rag.llm.numParallel`                                       | Concurrent requests per model                                                      | `1`                                             |
| `rag.llm.probe.initialDelaySeconds`                         | Grace period while the model is pulled and loaded                                  | `60`                                            |
| `rag.llm.probe.failureThreshold`                            | Readiness attempts while the model is still downloading                            | `240`                                           |
| `rag.llm.persistence.enabled`                               | Keep the pulled model across restarts                                              | `false`                                         |
| `rag.llm.persistence.spec.accessModes`                      | Access modes of the model cache claim                                              | `["ReadWriteOnce"]`                             |
| `rag.llm.persistence.spec.resources.requests.storage`       | Size of the model cache claim                                                      | `30Gi`                                          |
| `rag.llm.resources.limits.cpu`                              | Cores the answer service may use                                                   | `4`                                             |
| `rag.llm.resources.limits.memory`                           | Enough for a 1.5B model to stay resident                                           | `4Gi`                                           |
| `rag.llm.nodeSelector`                                      | Schedule the answer service, e.g. onto GPU nodes                                   | `{}`                                            |
| `rag.llm.tolerations`                                       | Tolerations for the answer service                                                 | `[]`                                            |
| `job.migration.enabled`                                     | Enable migration job                                                               | `false`                                         |
| `job.migration.image.name`                                  | Set name for migration job image                                                   | `${docker.prefix}-deploy-docker-build-tracker`  |
| `job.migration.image.tag`                                   | Set tag for migration job image                                                    | `${docker.tag}`                                 |
| `job.migration.podAnnotations`                              | Set pod annotations for migration job                                              | `{}`                                            |
| `job.migration.startupProbe.failureThreshold`               | Failure threshold for startupProbe                                                 | `300`                                           |
| `job.migration.startupProbe.initialDelaySeconds`            | Initial delay seconds for startupProbe                                             | `0`                                             |
| `job.migration.startupProbe.periodSeconds`                  | Period seconds for startupProbe                                                    | `20`                                            |
| `job.migration.startupProbe.successThreshold`               | Success threshold for startupProbe                                                 | `1`                                             |
| `job.migration.startupProbe.timeoutSeconds`                 | Timeout seconds for startupProbe                                                   | `10`                                            |
| `job.migration.livenessProbe.failureThreshold`              | Failure threshold for livenessProbe                                                | `3`                                             |
| `job.migration.livenessProbe.initialDelaySeconds`           | Initial delay seconds for livenessProbe                                            | `30`                                            |
| `job.migration.livenessProbe.periodSeconds`                 | Period seconds for livenessProbe                                                   | `30`                                            |
| `job.migration.livenessProbe.timeoutSeconds`                | Timeout seconds for livenessProbe                                                  | `10`                                            |
| `job.migration.readinessProbe.failureThreshold`             | Failure threshold for readinessProbe                                               | `1`                                             |
| `job.migration.readinessProbe.initialDelaySeconds`          | Initial delay seconds for readinessProbe                                           | `10`                                            |
| `job.migration.readinessProbe.periodSeconds`                | Period seconds for readinessProbe                                                  | `10`                                            |
| `job.migration.readinessProbe.successThreshold`             | Set threshold for success on readiness probe                                       | `1`                                             |
| `job.migration.readinessProbe.timeoutSeconds`               | Timeout seconds for readinessProbe                                                 | `10`                                            |
| `job.migration.resources.limits.cpu`                        | Set CPU limit on resources                                                         | `500m`                                          |
| `job.migration.resources.limits.memory`                     | Set memory limit on resources                                                      | `2Gi`                                           |
| `job.migration.resources.requests.cpu`                      | Set CPU for requests on resources                                                  | `500m`                                          |
| `job.migration.resources.requests.memory`                   | Set memory for requests on resources                                               | `2Gi`                                           |
| `job.migration.securityContext.runAsUser`                   | Set user to run migration job under                                                | `1000`                                          |
