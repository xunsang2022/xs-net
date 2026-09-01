# Contributing to xs-net

## Scope

Keep changes focused on one user-visible behavior or one infrastructure concern.
Do not commit credentials, local logs, generated directories, or benchmark data.

## Development workflow

1. Each unit of work runs on its own module branch forked from the current
   integration branch (`build-1.0`). Never commit directly on `build-1.0` or
   `master`. Name the branch after the module and intent, e.g.
   `feat/gateway-udp-reliability` or `fix/login-jwt-expiry`.
2. Add or update focused tests when behavior changes.
3. Run the Maven verification required by the changed reactor:
   `./mvnw.cmd -f xs-net/pom.xml test` and/or
   `./mvnw.cmd -f game-server/pom.xml test`.
4. After validation, merge the module branch back into `build-1.0` with a
   `--no-ff` merge, based on the latest `build-1.0`, then delete the merged
   local and remote branch. Do not merge into or push to `master` directly.
5. Describe the behavior, validation command, and any environment prerequisites
   in the pull request.

## Commit messages

Conventional Commits are required: `feat:`, `fix:`, `refactor:`, `test:`,
`docs:`, `build:`, and `chore:`, scoped where useful (e.g. `feat(game-gateway)`).
Keep the subject imperative, focused on a single change, and written in English.

## Reviews

Reviewers expect a clear rollback path for cluster, persistence, and protocol
changes. Changes to client-facing RPC numbers require compatibility analysis;
management and internal Actor messages must not be exposed through the player
transport without explicit authorization and versioning work.

## License status

The project-level outbound license remains a maintainer decision. Do not add a
license header, copy external source code, or claim a release license until that
decision and a dependency license audit are complete.
