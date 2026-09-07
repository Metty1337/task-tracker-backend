# Codex Git workflow via GitHub MCP

The project uses Python 3.11+ (standard library only), local Git, the official GitHub MCP server and Windows PowerShell
for notifications. GitHub CLI is not required. Codex executes GitHub MCP tools through its existing connection; the
Python script does not implement an MCP client, call GitHub REST or read the MCP token.

`SessionStart`/`UserPromptSubmit` explain the protocol and supply the session ID. `PreToolUse` creates the task
branch/worktree and checks MCP writes. `Stop` commits/pushes validated files and gives Codex the next MCP request.
`PostToolUse` processes the actual MCP result, verifies it and selects the next step. The agent identifies task
boundaries and reviews the work; scripts do not infer those decisions from prompt keywords.

## One-time setup

The initial installation is deliberately left uncommitted in the current branch. Before using the workflow, commit the
configuration and scripts and get them onto `origin`'s default branch through the usual manual process. New tasks start
from that branch; missing setup files cause a clear startup failure. Keep `AGENTS.md`, `.codex/config.toml`,
`.codex/hooks.json`, and the scripts listed in `.gitignore` under version control. Runtime state and worktrees under
`.codex-work/` stay ignored; do not remove this directory while work is active. Gradle `clean` does not remove it.

The committed `.codex/config.toml` defines `[mcp_servers.github]` with the official URL
`https://api.githubcopilot.com/mcp/` and `bearer_token_env_var = "GITHUB_MCP_TOKEN"`. Supply a GitHub PAT with access to
this repository and permission to read/write its pull requests through the environment of the IDE/Codex process.
Configure it privately through your environment/credential management, never in chat or a tracked file. A project `.env`
is not automatically loaded by this configuration. Restart the IDE after changing its inherited environment. Git push
uses Git's existing credential manager or SSH authentication independently of MCP.

Start a new Codex session, check the `github` connection using `/mcp` or the IDE MCP panel, and review/trust changed
hooks using `/hooks` in Codex CLI (or the client's hook review UI, if available). Codex skips new/changed hook
definitions until trusted. Do not edit trust hashes or bypass hook trust. Enabled tools are `get_me`,
`list_pull_requests`, `pull_request_read`, `create_pull_request` and `update_pull_request`; automatic merge tools are
not enabled. Server name `github` matters because hook events use the `mcp__github__` prefix.

See the
official [GitHub MCP configuration for Codex](https://github.com/github/github-mcp-server/blob/main/docs/installation-guides/install-codex.md)
and [Codex MCP configuration](https://developers.openai.com/codex/mcp). The hook command resolves its script from the
Git root, including when started in a subdirectory or linked worktree. Hook execution, tool availability and network
access remain subject to client/environment permissions.

## Commands used by the agent

Use the session ID provided by the hook. The human does not need to run these commands for each task.

~~~powershell
python scripts/codex_git_flow.py start --session SESSION_ID --slug add-task-editing
~~~

`start` only records intent. The next `PreToolUse` fetches the default branch, chooses an unused `codex/<slug>` branch,
creates its worktree, and denies that first pending tool call with the new path. Retry the tool there. Use its path as
`workdir` for shell commands and use absolute file paths for edits. The original checkout and its index remain
unchanged. Subsequent requests about the same task continue there; a new independent task gets a new branch.

Before publication, review the diff and write a manifest under the task worktree's ignored `build/` directory, for
example `build/git-flow-manifest.json`:

~~~json
{
  "title": "feat: add task editing",
  "body": "Allows the task owner to update the title and description.\n\nIncludes ownership regression coverage.",
  "files": ["src/main/java/example/TaskController.java", "src/test/java/example/TaskControllerTests.java"],
  "checks": [],
  "external_blocker": ""
}
~~~

Replace the example paths with every actual changed path in the isolated worktree (both old and new names for renames).
Unlisted changes reject preparation. `checks` contains additional commands as argument arrays, e.g.
`["python", "-m", "unittest", "discover", "-s", "scripts", "-p", "test_codex_git_flow.py"]`; no shell interpolation is
used. Commands are executable code and must be chosen by the agent for the authorized task. Java/source/Gradle changes
automatically run `gradlew.bat test`; changes to the workflow engine, its tests or hooks automatically run the Python
suite. Whitespace checks always run. No application test run is required for documentation-only changes.

~~~powershell
python scripts/codex_git_flow.py prepare --session SESSION_ID --manifest build/git-flow-manifest.json
~~~

`prepare` executes checks, records their real exit status/output, and saves a snapshot of HEAD, staged changes and file
content. If checks modify files or fail, review/fix and prepare again. Only an explicitly described external validation
blocker permits a draft PR with failed/unverified checks; do not use this for bugs caused by the task. Check output and
the generated PR body are saved under `.codex-work/state/` in the original checkout.

The next `Stop` verifies the snapshot, stages the exact reviewed paths, commits, pushes only the task branch and
verifies the remote commit. It records `awaiting_mcp` and resumes Codex with the tool name and exact JSON arguments.
Codex must execute that request through its connected GitHub MCP server, not through a shell command or another
transport.

## MCP publication and verification

1. `list_pull_requests` searches the task repository for `owner:branch` in all states, following pagination. An existing
   open PR is reused; closed/merged-only or ambiguous results block publication.
2. `create_pull_request` creates a missing PR. An existing PR is first read with `pull_request_read(method="get")`;
   `update_pull_request` changes only the prepared title/body/draft when needed.
3. A create/update response containing `{id,url}` is followed by `pull_request_read(method="get")`. Only a matching open
   PR with the expected repository, head/base branches, head SHA, title, body and draft flag changes the task to
   `published`.

`PreToolUse` admits a publication write only if it matches the queued request exactly, after push. It records
`tool_use_id` before execution. `PostToolUse` accepts only the matching tool, arguments and call ID, then decodes the
official server's JSON `TextContent` or `structuredContent`. Unrelated reads, stale/duplicate results and other sessions
cannot complete publication. Unknown response formats and MCP errors block the workflow rather than count as success.
The canonical wire formats are the official
server's [pull-request implementation](https://github.com/github/github-mcp-server/blob/main/pkg/github/pullrequests.go)
and [minimal result types](https://github.com/github/github-mcp-server/blob/main/pkg/github/minimal_types.go).

Execute publication calls sequentially. If a response is lost, `retry` lists PRs again before another write, so an
already-created PR is recovered. If MCP is disconnected or a call never completes, Stop gives a bounded continuation and
records the blocker. There is no fallback to GitHub CLI or direct REST. The final response must include the verified PR
URL and actual check results. The existing notification runs only when the response may finish. Questions do not create
tasks, and Plan mode does not commit, push or write a PR.

~~~powershell
# Inspect the task, reopen a prepared result, or request an MCP check before review fixes:
python scripts/codex_git_flow.py status --session SESSION_ID
python scripts/codex_git_flow.py edit --session SESSION_ID
python scripts/codex_git_flow.py resume --session SESSION_ID
# Pause publication when more work or user input is needed:
python scripts/codex_git_flow.py block --session SESSION_ID --reason 'Need clarification about the endpoint contract'
# After resolving a failure; uncommitted tasks then need prepare again:
python scripts/codex_git_flow.py retry --session SESSION_ID
# Only for an explicitly cancelled task; files and branch are retained:
python scripts/codex_git_flow.py cancel --session SESSION_ID --reason 'User cancelled this task'
~~~

`resume` queues an MCP read of the previously published PR. Only a verified open PR returns the task to `active` and
unlocks edits. Publication stops on authentication, network, branch, snapshot or PR conflicts and preserves the local
result. A saved commit is reused on retry, including recovery after losing Git's commit response. No merge, force-push,
branch deletion or worktree cleanup is automated. A per-repository OS lock prevents overlapping state changes; MCP calls
are correlated across separate hook invocations.

Local state format is version 2. Old CLI-workflow state is left untouched and rejected with its file path;
inspect/finish any old task before starting the MCP workflow in a new session. Configuration changes alone do not
migrate in-flight tasks or verify live authentication.

`PreToolUse` also checks supported edits and task shell working directories and rejects direct Git publication commands.
This is a workflow guard, not an exhaustive shell parser: arbitrary scripts, specialized tool paths and
disabled/untrusted hooks can bypass it. The agent must still honor ownership of task files and review the manifest.
Hooks remain command handlers because they coordinate conditional Git and MCP steps; the agent's MCP calls are observed
through Pre/PostToolUse rather than a separate Python MCP connection. See the
official [hook event and trust contract](https://learn.chatgpt.com/docs/hooks).

## Workflow tests

~~~powershell
python -m unittest discover -s scripts -p test_codex_git_flow.py -v
~~~

Tests create temporary repositories and a local bare `origin` under `build/git-flow-tests/`. They execute real Git
branch/worktree/commit/push operations and feed realistic GitHub MCP `PreToolUse`/`PostToolUse` events with official
result shapes into the hooks. They cover PR reuse, draft transitions, pagination, strict argument checks, SHA
verification, correlation and recovery after lost responses. Any attempted GitHub CLI invocation fails a test. No live
GitHub PR is created; this verifies the workflow and hook launcher, not live MCP authentication or IDE hook loading.

# Startup check

From the repository root on Windows with Java 25:

~~~powershell
powershell -NoProfile -File .\scripts\check-startup.ps1
# Optional application startup timeout (build time is separate):
powershell -NoProfile -File .\scripts\check-startup.ps1 -TimeoutSeconds 120
~~~

The command builds the executable JAR using the Gradle wrapper, starts it on a temporary loopback port, waits for the
Spring Boot startup message and an HTTP response, then stops the process it created. Exit code is 0 on success and 1 on
failure. Each run stores build and application logs in a separate directory under the ignored `build/startup-check/`
folder.

Configure the application's normal dependencies and environment variables first. The command inherits them; it does not
provision PostgreSQL/Kafka or change application configuration. Starting the application can run its configured
migrations and startup jobs, so use a local development environment.

HTTP 200-499 is accepted, including authentication errors and a missing root route. This checks startup and HTTP
availability, not API correctness or downstream service health. Run `.\gradlew.bat test` separately.

The startup log detection follows this project's embedded Tomcat and `TaskTrackerBackendApplication` class name. If
these change or startup logging is disabled, update the script. If old executable JARs remain in `build/libs`, the
script fails rather than choosing an arbitrary version.
