# Contributing to xs-net

## Scope

Keep changes focused on one user-visible behavior or one infrastructure concern.
Do not commit credentials, local logs, generated directories, or benchmark data.

## Development workflow

1. Branch from the current integration branch using a descriptive name.
2. Add or update focused tests when behavior changes.
3. Run the Maven verification required by the changed reactor:
   `./mvnw.cmd -f xs-net/pom.xml test` and/or
   `./mvnw.cmd -f game-server/pom.xml test`.
4. Describe the behavior, validation command, and any environment prerequisites
   in the pull request.

## Commit messages

Conventional Commits are recommended: `feat:`, `fix:`, `docs:`, `test:`,
`build:`, and `chore:`. Keep the subject imperative and scoped.

## Reviews

Reviewers expect a clear rollback path for cluster, persistence, and protocol
changes. Changes to client-facing RPC numbers require compatibility analysis;
management and internal Actor messages must not be exposed through the player
transport without explicit authorization and versioning work.

## License status

The project-level outbound license remains a maintainer decision. Do not add a
license header, copy external source code, or claim a release license until that
decision and a dependency license audit are complete.
