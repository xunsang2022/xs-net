# Changelog

This file follows the Keep a Changelog format. Versions are created only when a
maintainer approves a release.

## Unreleased

### Changed

- Migrated the actor runtime from Akka 2.8.5 to Apache Pekko 1.1.5.

### Added

- Runtime Logic configuration reload, Prometheus metrics endpoint, and
  observability deployment configuration.
- Authenticated GM kick command path with Redis Streams audit logging.
- Cluster Compose topology and initial SceneActor membership support.
