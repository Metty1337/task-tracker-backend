# Repository Guidelines

## Service Scope & Target Requirements

This repository implements the **Backend (Бэкенд)** service from
the [Task Tracker specification](https://zhukovsd.github.io/java-backend-learning-course/projects/task-tracker/). The
following requirements are targets, not implemented features.

- Provide REST APIs for users, authentication, and user-owned tasks. Scheduler, email delivery, and LLM summarization
  belong to separate services.
- Use Spring Security with JWT access tokens; refresh tokens are optional.
- `POST /user`: register and automatically authenticate; return HTTP 200 with JWT in a response header. Duplicate email
  returns 409.
- `POST /auth/login`: return HTTP 200 with JWT in a response header. Registration/login accept JSON or form-encoded
  fields.
- `GET /user`: return `id` and `email`; `GET /tasks`: return the current user's task array. Both return 401 for
  missing/expired authentication. Errors use JSON with a `message` field and appropriate HTTP status.
- Design task creation, editing, completion/reopening, and deletion endpoints; document chosen contracts.
- Persist users and tasks in PostgreSQL with a one-to-many relationship. Tasks contain title, description, owner,
  status, and completion timestamp. Decide schema-migration ownership explicitly.
- After registration, publish a JSON email request containing recipient, subject, and body to Kafka topic
  `EMAIL_SENDING_TASKS`.
- Target backend port: `8080` in the Docker Compose stack.

## Project Structure & Module Organization

This is a single-module Java 25 Spring Boot backend using Spring MVC, Spring Data JPA, Spring Security, and Lombok.

- `src/main/java/metty1337/task/tracker/backend`: application code; keep new packages beneath this root for component
  scanning.
- `src/main/resources/application.properties`: application configuration.
- `src/test/java/metty1337/task/tracker/backend`: tests mirroring the production package structure.
- `build.gradle.kts` and `settings.gradle.kts`: Gradle Kotlin DSL build configuration.
- `gradle/wrapper/` and `gradlew*`: pinned Gradle wrapper files.

The repository currently contains only the application entry point and a context-loading test. Add feature packages as
functionality develops.

## Build, Test, and Development Commands

Use the committed wrapper with a Java 25 JDK. From the repository root on Windows:

- `.\gradlew.bat build`: compile, run tests, and package the application.
- `.\gradlew.bat test`: run the test suite through JUnit Platform.
- `.\gradlew.bat bootRun`: start the application locally.
- `.\gradlew.bat bootJar`: create the executable Spring Boot JAR under `build/libs/`.
- `powershell -NoProfile -File .\scripts\check-startup.ps1`: build the JAR, verify Spring startup and an HTTP response
  on a temporary port, then stop the process. Requires configured runtime dependencies (including PostgreSQL); inherits
  environment variables. Logs are written beneath `build/startup-check/`. This does not replace the test suite.

On Unix-like systems, replace `.\gradlew.bat` with `./gradlew`.

## Coding Style & Naming Conventions

Follow the existing four-space indentation and same-line opening braces. Use lowercase package names, `PascalCase`
classes, `camelCase` methods and fields, and `UPPER_SNAKE_CASE` constants. Keep Java sources within the existing package
root. Lombok is available for annotation processing; use it consistently where introduced. No formatter or lint plugin
is currently configured.

## Testing Guidelines

Use JUnit Jupiter; the existing `TaskTrackerBackendApplicationTests` uses `@SpringBootTest` for context loading. Name
test classes `*Tests` and methods descriptively, such as `rejectsMissingTitle`. Prefer focused unit tests for isolated
logic and Spring integration tests for framework wiring. Run the suite before submitting changes. No coverage threshold
is configured.

## Commit & Pull Request Guidelines

Follow Conventional Commit style with concise, imperative subjects, for example `feat: add task creation`. Keep changes
focused. Pull requests should explain behavior changes, link relevant issues, and list validation commands and results.
Document API or configuration changes when applicable.

## Autonomous Git Workflow

- For subsequent tasks that change this repository, the user authorizes Codex to create a task branch, commit, push that
  branch to `origin`, and create or update a GitHub PR without asking for confirmation again. Follow explicit
  task-specific overrides and environment permissions. Questions, planning, and reviews without requested edits do not
  require a branch, commit, or PR.
- The implementation lives in `.codex/hooks.json` and `scripts/codex_git_flow.py`; follow the command/manifest contract
  in `scripts/README.md`. `SessionStart` and `UserPromptSubmit` supply the session ID. Register intent with
  `start --session <id> --slug <short-task-name>` before a new editing task. The next `PreToolUse` hook fetches
  `origin`, determines its default branch, and creates `codex/<short-task-name>` in an isolated worktree, adding a
  numeric suffix for collisions. It returns the worktree path; use that workdir and absolute paths for all task edits.
- Continue the same task, including follow-up requests and review fixes, in its existing branch and PR. Do not create a
  new branch for each message. After a PR is merged or closed, treat further requested changes as a new task.
- Preserve unrelated staged, unstaged, and untracked changes. Each task gets its own worktree under
  `.codex-work/worktrees/`; local state and publication records live under `.codex-work/state/`. This directory is
  ignored and must not be deleted while tasks are active. Do not discard, automatically stash, or include the user's
  unrelated work in a commit. `AGENTS.md`, hook configuration and supporting scripts must be committed to the default
  branch before the workflow can create complete task worktrees.
- Implement the task, run the relevant repository checks, and inspect the final diff before committing. Run the Java
  test suite for application or build changes; documentation-only and agent-instruction changes need a diff review and
  `git diff --check`, not Java tests or a startup check. Fix failures caused by the task before publishing.
- Review all task-worktree changes and prepare a manifest with the exact changed paths, a Conventional Commit title, PR
  body, and relevant check commands. Run `prepare --session <id> --manifest <path>`. This runs validation and binds it
  to the current files and HEAD. Use `edit` to reopen a prepared result before further edits; use `resume` for review
  fixes after publication.
- `Stop` verifies the prepared snapshot, stages only the manifest paths, commits and pushes the task branch using local
  Git. It then supplies an exact request for the connected `github` MCP server. Execute these MCP requests sequentially;
  `PreToolUse` checks their arguments and `PostToolUse` validates and records the results, supplying the next request.
  The sequence lists existing PRs, creates or updates as needed, and reads the PR again to verify the repository,
  branches, head SHA, title, body and draft status. A create response containing a URL alone is not proof of completion.
  Do not substitute GitHub CLI or direct REST calls.
- Create a normal PR when the work is complete and checks pass. If external dependencies prevent validation, publish a
  draft PR that clearly identifies the unverified behavior and blocker. Keep an existing PR in draft while such blockers
  remain and mark it ready when resolved.
- Return the PR URL in the final chat response with a short explanation of changes, checks and their results, and any
  remaining limitations. Do not automatically request a named GitHub reviewer; the user reviews via the link in chat.
- Never automatically merge a PR, push directly to the default branch, or force-push. Commit, push, and PR authorization
  does not bypass sandbox or organizational restrictions. If authentication, permissions, or network access blocks a
  step, preserve completed work, report the exact blocker and remaining step, and do not claim publication succeeded.
  GitHub MCP is configured in `.codex/config.toml`; the IDE/Codex process must receive `GITHUB_MCP_TOKEN` from the
  environment. Confirm the `github` server is connected in `/mcp` or the IDE's MCP UI. Never request credentials in chat
  or store them in project files. Git push uses Git's separate credential/SSH configuration.
- If work is unfinished or needs user input, record `block --session <id> --reason <concrete blocker>` so Stop does not
  publish it. After resolving the blocker use `retry`; uncommitted changes require preparation again. After a lost MCP
  response, retry re-reads GitHub before any write, reusing the saved commit. `resume` also requires a successful MCP
  read of the existing open PR before edits can continue. Use `cancel` only when the user explicitly cancels the task;
  it preserves the branch and worktree. Hooks are workflow checks, not a complete security boundary for arbitrary shell
  commands or tools.
- These rules govern future tasks. The initial installation of this workflow is explicitly exempt: edit the instructions
  in the current branch and leave them uncommitted, without pushing or creating a PR.

## Security & Configuration

Never commit credentials. Supply secrets through environment variables or external configuration. JPA is included, but
no database driver or datasource settings are configured; provide these when implementing persistence or running
database-dependent context tests.

## Working Conventions

- Before editing, inspect the nearest existing implementation and follow its conventions. Keep changes within the
  requested scope.
- Enforce current-user ownership for task reads and mutations. When changing access control, verify that another user
  cannot access or modify the task.
- When changing an API, update its documented contract and a request example. Distinguish specification requirements
  from locally chosen contracts.
- Add dependencies and abstractions only for a concrete current need; explain why a new dependency is necessary.
- For learning questions, explain the relevant flow and reasoning. Implement changes when the request calls for
  implementation.
- In the final response, report behavior changes, checks actually run and their results, and any unverified behavior. Do
  not claim success from a command that was not run or failed.
- Review requests produce findings by default; apply fixes when requested. Missing target features are specification
  gaps, not automatically regressions in a focused code review.

## Startup Check

Run `powershell -NoProfile -File .\scripts\check-startup.ps1` from the repository root. The command builds the JAR,
verifies Spring startup and an HTTP response on a temporary loopback port, then stops its process. Requires Java 25 and
configured runtime dependencies, including PostgreSQL; inherits environment variables. Logs are under
`build/startup-check/`. HTTP 401/403/404 are accepted as proof that the server responds, not proof of API correctness.
This does not replace the test suite. The timeout can be set with `-TimeoutSeconds 120` and applies after the build.
