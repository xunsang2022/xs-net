# xs-net Roadmap

## Completed foundation

- Apache Pekko 1.1.5 migration for the current Maven reactors, Java APIs, and
  cluster configuration.
- Basic Logic metrics endpoint and Prometheus/Grafana deployment assets.
- Secure-by-default GM kick command path with audit logging.
- SceneActor membership skeleton for service-line experiments.

## Next

- Measure the two-Logic-node topology and publish the same-workload comparison.
- Add GM ban and state-edit operations only after their persistence schema,
  authorization roles, and audit retention policy are defined.
- Persist scene lifecycle state and implement service-line allocation, merge,
  and recovery.
- Add behavior-event sampling and offline risk analysis before automated
  enforcement.

## Before external release

- Complete the project license choice and dependency license audit.
- Publish a minimal playable example and Quick Start validation result.
- Define release versioning, changelog ownership, and issue triage policy.
