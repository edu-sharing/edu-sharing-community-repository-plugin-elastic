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
| `global.metrics.scrape.interval`        | Set prometheus scrape interval        | `60s`                  |
| `global.metrics.scrape.timeout`         | Set prometheus scrape timeout         | `60s`                  |
| `global.metrics.servicemonitor.enabled` | Enable metrics service monitor        | `false`                |
| `global.password`                       | Set global password                   | `""`                   |
| `global.security`                       | Set global custom security parameters | `{}`                   |

### Local parameters

| Name                                               | Description                                    | Value                                          |
| -------------------------------------------------- | ---------------------------------------------- | ---------------------------------------------- |
| `nameOverride`                                     | Override name                                  | `edusharing-repository-search-elastic-tracker` |
| `image.name`                                       | Set image name                                 | `${docker.prefix}-deploy-docker-build-tracker` |
| `image.tag`                                        | Set image tag                                  | `${docker.tag}`                                |
| `replicaCount`                                     | Define amount of parallel replicas to run      | `1`                                            |
| `service.port.management`                          | Set port for service management                | `8081`                                         |
| `config.jvm.ram.minPercentage`                     | Set minimum memory in percentages              | `90.0`                                         |
| `config.jvm.ram.maxPercentage`                     | Set maximum memory in percentages              | `90.0`                                         |
| `config.metrics.enabled`                           | Enable metrics                                 | `true`                                         |
| `config.metrics.relabelings`                       | Define relabelings for metrics                 | `[]`                                           |
| `config.override`                                  | Configure overrides                            | `""`                                           |
| `config.repository.host`                           | Configure repository host                      | `edusharing-repository-service`                |
| `config.repository.port`                           | Configure repository port                      | `8080`                                         |
| `config.repository.password`                       | Configure repository password                  | `""`                                           |
| `config.tracker.customScript`                      | Configure a custom script for the tracker      | `""`                                           |
| `config.tracker.customSynonyms`                    | Configure custom synonyms file for the tracker | `""`                                           |
| `config.search.elastic.host`                       | Set elasticsearch host                         | `edusharing-repository-search-elastic-index`   |
| `config.search.elastic.port`                       | Set elasticsearch port                         | `9200`                                         |
| `config.search.elastic.index.shards`               | Set elasticsearch index shards                 | `1`                                            |
| `config.search.elastic.index.replicas`             | Set elasticsearch index replicas               | `2`                                            |
| `multistage.enabled`                               | Enable multistage                              | `false`                                        |
| `multistage.stages`                                | Set stages for multistage                      | `[]`                                           |
| `debug`                                            | Enable debugging                               | `false`                                        |
| `nodeAffinity`                                     | Set node affinity                              | `{}`                                           |
| `tolerations`                                      | Set tolerations                                | `[]`                                           |
| `podAnnotations`                                   | Set custom pod annotations                     | `{}`                                           |
| `podSecurityContext.fsGroup`                       | Set fs group for access                        | `1000`                                         |
| `podSecurityContext.fsGroupChangePolicy`           | Set change policy for fs group                 | `OnRootMismatch`                               |
| `securityContext.allowPrivilegeEscalation`         | Allow privilege escalation                     | `false`                                        |
| `securityContext.capabilities.drop`                | Set drop capabilities                          | `["ALL"]`                                      |
| `securityContext.runAsUser`                        | Define user to run under                       | `1000`                                         |
| `terminationGracePeriod`                           | Define grace period for termination            | `120`                                          |
| `startupProbe.failureThreshold`                    | Failure threshold for startupProbe             | `30`                                           |
| `startupProbe.initialDelaySeconds`                 | Initial delay seconds for startupProbe         | `0`                                            |
| `startupProbe.periodSeconds`                       | Period seconds for startupProbe                | `20`                                           |
| `startupProbe.successThreshold`                    | Success threshold for startupProbe             | `1`                                            |
| `startupProbe.timeoutSeconds`                      | Timeout seconds for startupProbe               | `10`                                           |
| `livenessProbe.failureThreshold`                   | Failure threshold for livenessProbe            | `3`                                            |
| `livenessProbe.initialDelaySeconds`                | Initial delay seconds for livenessProbe        | `30`                                           |
| `livenessProbe.periodSeconds`                      | Period seconds for livenessProbe               | `30`                                           |
| `livenessProbe.timeoutSeconds`                     | Timeout seconds for livenessProbe              | `10`                                           |
| `readinessProbe.failureThreshold`                  | Failure threshold for readinessProbe           | `1`                                            |
| `readinessProbe.initialDelaySeconds`               | Initial delay seconds for readinessProbe       | `10`                                           |
| `readinessProbe.periodSeconds`                     | Period seconds for readinessProbe              | `10`                                           |
| `readinessProbe.successThreshold`                  | Set threshold for success on readiness probe   | `1`                                            |
| `readinessProbe.timeoutSeconds`                    | Timeout seconds for readinessProbe             | `10`                                           |
| `resources.limits.cpu`                             | Set CPU limit on resources                     | `500m`                                         |
| `resources.limits.memory`                          | Set memory limit on resources                  | `2Gi`                                          |
| `resources.requests.cpu`                           | Set CPU for requests on resources              | `500m`                                         |
| `resources.requests.memory`                        | Set memory for requests on resources           | `2Gi`                                          |
| `job.migration.enabled`                            | Enable migration job                           | `false`                                        |
| `job.migration.image.name`                         | Set name for migration job image               | `${docker.prefix}-deploy-docker-build-tracker` |
| `job.migration.image.tag`                          | Set tag for migration job image                | `${docker.tag}`                                |
| `job.migration.podAnnotations`                     | Set pod annotations for migration job          | `{}`                                           |
| `job.migration.startupProbe.failureThreshold`      | Failure threshold for startupProbe             | `300`                                          |
| `job.migration.startupProbe.initialDelaySeconds`   | Initial delay seconds for startupProbe         | `0`                                            |
| `job.migration.startupProbe.periodSeconds`         | Period seconds for startupProbe                | `20`                                           |
| `job.migration.startupProbe.successThreshold`      | Success threshold for startupProbe             | `1`                                            |
| `job.migration.startupProbe.timeoutSeconds`        | Timeout seconds for startupProbe               | `10`                                           |
| `job.migration.livenessProbe.failureThreshold`     | Failure threshold for livenessProbe            | `3`                                            |
| `job.migration.livenessProbe.initialDelaySeconds`  | Initial delay seconds for livenessProbe        | `30`                                           |
| `job.migration.livenessProbe.periodSeconds`        | Period seconds for livenessProbe               | `30`                                           |
| `job.migration.livenessProbe.timeoutSeconds`       | Timeout seconds for livenessProbe              | `10`                                           |
| `job.migration.readinessProbe.failureThreshold`    | Failure threshold for readinessProbe           | `1`                                            |
| `job.migration.readinessProbe.initialDelaySeconds` | Initial delay seconds for readinessProbe       | `10`                                           |
| `job.migration.readinessProbe.periodSeconds`       | Period seconds for readinessProbe              | `10`                                           |
| `job.migration.readinessProbe.successThreshold`    | Set threshold for success on readiness probe   | `1`                                            |
| `job.migration.readinessProbe.timeoutSeconds`      | Timeout seconds for readinessProbe             | `10`                                           |
| `job.migration.resources.limits.cpu`               | Set CPU limit on resources                     | `500m`                                         |
| `job.migration.resources.limits.memory`            | Set memory limit on resources                  | `2Gi`                                          |
| `job.migration.resources.requests.cpu`             | Set CPU for requests on resources              | `500m`                                         |
| `job.migration.resources.requests.memory`          | Set memory for requests on resources           | `2Gi`                                          |
| `job.migration.securityContext.runAsUser`          | Set user to run migration job under            | `1000`                                         |
