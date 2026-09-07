"""Local Git workflow with GitHub MCP publication, coordinated by Codex hooks."""

import argparse
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from urllib.parse import urlparse


MCP_PREFIX = "mcp__github__"
STATE_VERSION = 2


class FlowError(Exception):
    pass


def run(cwd, *argv, timeout=90, include_stderr=False):
    env = dict(os.environ, GIT_TERMINAL_PROMPT="0", GCM_INTERACTIVE="Never", GIT_LITERAL_PATHSPECS="1")
    result = subprocess.run(argv, cwd=cwd, env=env, capture_output=True,
                            encoding="utf-8", errors="replace", timeout=timeout)
    if result.returncode:
        raise FlowError(f"{argv[0]} {argv[1] if len(argv) > 1 else ''} failed "
                        f"({result.returncode}): {(result.stderr or result.stdout)[-3000:]}")
    output = result.stdout + (result.stderr if include_stderr else "")
    return output.strip() if "-z" not in argv else output


def git(cwd, *args):
    return run(cwd, "git", *args)


def read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def repository_from_origin(origin):
    if origin.startswith("git@github.com:"):
        path = origin[len("git@github.com:"):]
    else:
        parsed = urlparse(origin)
        if parsed.scheme not in ("https", "ssh") or parsed.hostname != "github.com" or parsed.password:
            raise FlowError("GitHub MCP requires a github.com HTTPS or SSH origin without embedded credentials.")
        if parsed.scheme == "https" and parsed.username:
            raise FlowError("Remove embedded credentials from the HTTPS origin; use Git's credential manager.")
        path = parsed.path.lstrip("/")
    path = path.rstrip("/").removesuffix(".git")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", path):
        raise FlowError("Cannot determine the GitHub owner/repository from origin.")
    owner, repo = path.split("/")
    return {"owner": owner, "repo": repo}


def mcp_payload(response):
    """Decode the official server's JSON TextContent or structuredContent."""
    if not isinstance(response, dict):
        raise FlowError("Missing MCP CallToolResult.")
    if response.get("isError"):
        raise FlowError("GitHub MCP returned an error. Inspect the tool result and resolve access/network errors before retry.")
    if "structuredContent" in response and response["structuredContent"] is not None:
        return response["structuredContent"]
    blocks = [item.get("text", "") for item in response.get("content", []) if item.get("type") == "text"]
    if len(blocks) != 1:
        raise FlowError("Expected one JSON text block from GitHub MCP; publication is not verified.")
    try:
        return json.loads(blocks[0])
    except (ValueError, TypeError) as exc:
        raise FlowError("GitHub MCP returned non-JSON content; publication is not verified.") from exc


class Flow:
    def __init__(self, cwd, session):
        if not session:
            raise FlowError("A Codex session_id is required.")
        self.root = Path(git(cwd, "rev-parse", "--show-toplevel")).resolve()
        common = Path(git(cwd, "rev-parse", "--path-format=absolute", "--git-common-dir"))
        self.controller = common.resolve().parent
        self.directory = self.controller / ".codex-work" / "state"
        key = hashlib.sha256(session.encode()).hexdigest()[:24]
        self.path = self.directory / (key + ".json")
        self.session = session

    @contextmanager
    def lock(self):
        # OS locks release automatically if a hook is interrupted or killed.
        self.directory.mkdir(parents=True, exist_ok=True)
        with (self.directory / "workflow.lock").open("a+b") as handle:
            handle.seek(0)
            if not handle.read(1):
                handle.write(b"0")
                handle.flush()
            handle.seek(0)
            try:
                if os.name == "nt":
                    import msvcrt
                    msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
                else:
                    import fcntl
                    fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except OSError as exc:
                raise FlowError("Another Git-flow operation is running; retry after it finishes.") from exc
            try:
                yield
            finally:
                if os.name == "nt":
                    handle.seek(0)
                    msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
                else:
                    fcntl.flock(handle, fcntl.LOCK_UN)

    def load(self):
        state = read_json(self.path) if self.path.exists() else None
        if state and state.get("version") != STATE_VERSION:
            raise FlowError("Legacy Git-flow state is preserved at " + str(self.path) +
                            ". Finish/inspect the old task before using the MCP workflow in a new session.")
        return state

    def save(self, state):
        write_json(self.path, state)

    def request_start(self, slug):
        if not re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", slug or "") or len(slug) > 60:
            raise FlowError("Use a task slug of 1-60 lowercase ASCII letters, digits and hyphens.")
        previous = self.load()
        if previous and previous["status"] not in ("published", "cancelled"):
            raise FlowError("This session already has a task. Resume it or explicitly cancel it first.")
        self.save({"version": STATE_VERSION, "session": self.session, "status": "pending", "slug": slug})
        return "Task registered. The next PreToolUse hook will create its branch and worktree."

    def activate(self, state):
        origin = git(self.controller, "remote", "get-url", "origin")
        repository = repository_from_origin(origin)
        push_origin = git(self.controller, "remote", "get-url", "--push", "origin")
        if repository_from_origin(push_origin) != repository:
            raise FlowError("Fetch and push URLs must refer to the same GitHub repository.")
        git(self.controller, "fetch", "--prune", "origin")
        remote_head = git(self.controller, "ls-remote", "--symref", "origin", "HEAD")
        match = re.search(r"^ref: refs/heads/(.+)\s+HEAD$", remote_head, re.M)
        if not match:
            raise FlowError("Cannot determine origin's default branch.")
        base = match.group(1).strip()
        # The setup itself is left uncommitted by request. Do not silently create
        # worktrees without their hooks before the setup reaches the default branch.
        for required in ("AGENTS.md", ".codex/config.toml", ".codex/hooks.json", "scripts/codex_git_flow.py"):
            try:
                git(self.controller, "cat-file", "-e", f"origin/{base}:{required}")
            except FlowError as exc:
                raise FlowError(f"Git-flow setup is missing {required} on origin/{base}. "
                                "Commit and publish the setup through the usual manual process first.") from exc
        refs = git(self.controller, "for-each-ref", "--format=%(refname)",
                   "refs/heads", "refs/remotes/origin").splitlines()
        suffix = 1
        while True:
            slug = state["slug"] + (f"-{suffix}" if suffix > 1 else "")
            branch = "codex/" + slug
            worktree = self.controller / ".codex-work" / "worktrees" / slug
            if (f"refs/heads/{branch}" not in refs and
                    f"refs/remotes/origin/{branch}" not in refs and not worktree.exists()):
                break
            suffix += 1
        worktree.parent.mkdir(parents=True, exist_ok=True)
        git(self.controller, "worktree", "add", "-b", branch, str(worktree), f"origin/{base}")
        state.update(status="active", branch=branch, worktree=str(worktree), base=base,
                     origin=origin, push_origin=push_origin, repository=repository,
                     start_head=git(worktree, "rev-parse", "HEAD"))
        self.save(state)

    def assert_branch(self, state):
        worktree = Path(state["worktree"])
        if git(worktree, "branch", "--show-current") != state["branch"]:
            raise FlowError("The task worktree is on a different branch; restore the task branch first.")
        if not state["branch"].startswith("codex/") or state["branch"] == state["base"]:
            raise FlowError("Refusing publication outside a task branch.")
        if git(worktree, "remote", "get-url", "origin") != state["origin"]:
            raise FlowError("origin changed since this task started.")
        if git(worktree, "remote", "get-url", "--push", "origin") != state["push_origin"]:
            raise FlowError("origin's push URL changed since this task started.")
        return worktree

    def changed_files(self, worktree):
        entries = git(worktree, "status", "--porcelain=v1", "-z", "--untracked-files=all").split("\0")
        paths = set()
        index = 0
        while index < len(entries) and entries[index]:
            entry = entries[index]
            if "U" in entry[:2] or entry[:2] in ("AA", "DD"):
                raise FlowError("Resolve merge conflicts before preparing publication.")
            paths.add(entry[3:])
            if "R" in entry[:2] or "C" in entry[:2]:
                index += 1
                paths.add(entries[index])
            index += 1
        return paths

    def snapshot(self, worktree, paths):
        digest = hashlib.sha256()
        digest.update(git(worktree, "rev-parse", "HEAD").encode())
        digest.update(git(worktree, "diff", "--cached", "--binary").encode())
        digest.update(git(worktree, "diff", "--binary").encode())
        for name in sorted(paths):
            path = worktree / name
            if path.is_symlink() or not path.resolve().is_relative_to(worktree.resolve()):
                raise FlowError(f"Refusing a symlink or path outside the worktree: {name}")
            digest.update(name.encode())
            digest.update(path.read_bytes() if path.is_file() else b"<deleted>")
        return digest.hexdigest()

    def prepare(self, manifest):
        state = self.load()
        if not state or state["status"] not in ("active", "ready", "blocked") or state.get("commit_tree"):
            raise FlowError("Prepare requires an active, uncommitted task; use retry for a committed publication.")
        worktree = self.assert_branch(state)
        title, body, files = manifest.get("title", ""), manifest.get("body", ""), manifest.get("files", [])
        if not re.fullmatch(r"(?:feat|fix|docs|style|refactor|perf|test|build|ci|chore|revert)(?:\([^\n)]+\))?!?: [^\r\n]+", title):
            raise FlowError("title must be a one-line Conventional Commit subject.")
        if not isinstance(body, str) or not body.strip() or not isinstance(files, list) or not files:
            raise FlowError("Provide a nonempty PR body and an explicit files array.")
        for name in files:
            if (not isinstance(name, str) or not name or "\\" in name or Path(name).is_absolute()
                    or ".." in Path(name).parts or name.startswith((".git/", ".codex-work/", "build/"))):
                raise FlowError(f"Invalid repository-relative path: {name!r}")
        changed = self.changed_files(worktree)
        if set(files) != changed:
            raise FlowError("files must match all changed task-worktree paths exactly (including both sides of a rename). "
                            f"Changed paths: {sorted(changed)}")
        before = self.snapshot(worktree, files)
        git(worktree, "diff", "--check")
        git(worktree, "diff", "--cached", "--check")
        checks = manifest.get("checks", [])
        if not isinstance(checks, list) or any(not isinstance(cmd, list) or not cmd or
                any(not isinstance(arg, str) or not arg for arg in cmd) for cmd in checks):
            raise FlowError("checks must be arrays of command arguments, without shell operators.")
        checks = list(checks)
        if any(name.startswith(("src/", "gradle/")) or name in
               ("build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat") for name in files):
            checks.insert(0, [str(worktree / ("gradlew.bat" if os.name == "nt" else "gradlew")), "test"])
        if any(name in ("scripts/codex_git_flow.py", "scripts/test_codex_git_flow.py", ".codex/hooks.json") for name in files):
            checks.insert(0, [sys.executable, "-m", "unittest", "discover", "-s", "scripts", "-p", "test_codex_git_flow.py"])
        results = ["PASS: git diff --check and git diff --cached --check"]
        failures = False
        log_path = self.path.with_suffix(".validation.log")
        with log_path.open("w", encoding="utf-8") as log:
            for command in checks:
                try:
                    output = run(worktree, *command, timeout=600, include_stderr=True)
                    results.append("PASS: " + subprocess.list2cmdline(command))
                except (FlowError, subprocess.TimeoutExpired, OSError) as exc:
                    output = str(exc)
                    failures = True
                    results.append("FAIL: " + subprocess.list2cmdline(command))
                log.write(results[-1] + "\n" + output + "\n")
        if self.changed_files(worktree) != changed or self.snapshot(worktree, files) != before:
            state.update(status="active")
            self.save(state)
            raise FlowError("Checks changed task files; review them and prepare again.")
        blocker = manifest.get("external_blocker", "")
        if not isinstance(blocker, str):
            raise FlowError("external_blocker must be text describing an external validation limitation.")
        if failures and not blocker.strip():
            state.update(status="active")
            self.save(state)
            raise FlowError(f"Validation failed. Fix task errors; logs: {log_path}")
        validation = "\n\nValidation:\n" + "\n".join("- " + item for item in results)
        if blocker.strip():
            validation += "\n\nExternal blocker / unverified behavior:\n" + blocker.strip()
        state.update(status="ready", title=title, body=body.rstrip() + validation,
                     files=sorted(changed), snapshot=before, draft=bool(blocker.strip()))
        state.pop("error", None)
        self.save(state)
        return f"Prepared and validated. Stop will commit/push, then request GitHub MCP publication. Logs: {log_path}"

    def publish(self, state):
        worktree = self.assert_branch(state)
        # Recover if Git finished the commit but the hook was interrupted before
        # recording its hash. The journal binds the intended tree and parent.
        if not state.get("commit") and state.get("commit_tree"):
            head = git(worktree, "rev-parse", "HEAD")
            if head != state["commit_parent"]:
                self.record_commit(worktree, state)
        if not state.get("commit"):
            if (self.changed_files(worktree) != set(state["files"]) or
                    self.snapshot(worktree, state["files"]) != state["snapshot"]):
                state.update(status="active")
                self.save(state)
                raise FlowError("Task changed after validation. Review and prepare again.")
            tracked = set(git(worktree, "ls-files", "-z").split("\0"))
            to_stage = [name for name in state["files"] if name in tracked or (worktree / name).exists()]
            if to_stage:
                git(worktree, "add", "--", *to_stage)
            staged = set(git(worktree, "diff", "--cached", "--name-only", "--no-renames", "-z").split("\0")) - {""}
            if staged != set(state["files"]):
                raise FlowError("Staged paths differ from the reviewed manifest.")
            git(worktree, "diff", "--cached", "--check")
            state.update(commit_parent=git(worktree, "rev-parse", "HEAD"),
                         commit_tree=git(worktree, "write-tree"),
                         snapshot=self.snapshot(worktree, state["files"]))
            self.save(state)
            git(worktree, "commit", "-m", state["title"])
            self.record_commit(worktree, state)
        if self.changed_files(worktree) or git(worktree, "rev-parse", "HEAD") != state["commit"]:
            raise FlowError("Task changed after committing. Restore its state before retrying publication.")
        git(worktree, "push", "-u", "origin", state["branch"])
        remote = git(worktree, "ls-remote", "origin", "refs/heads/" + state["branch"])
        if not remote or remote.split()[0] != state["commit"]:
            raise FlowError("Remote task branch does not match the committed result.")
        state.update(status="awaiting_mcp", pushed=True)
        self.queue_lookup(state)
        return self.mcp_instruction(state)

    def record_commit(self, worktree, state):
        actual = git(worktree, "show", "-s", "--format=%P%n%T%n%s", "HEAD").splitlines()
        if actual != [state["commit_parent"], state["commit_tree"], state["title"]]:
            raise FlowError("Committed result differs from the prepared tree, parent or title; inspect local Git hooks/history.")
        state["commit"] = git(worktree, "rev-parse", "HEAD")
        self.save(state)

    def resume(self):
        state = self.load()
        if not state or state["status"] != "published":
            raise FlowError("resume requires a published task; blocked publication uses retry.")
        self.assert_pushed(state)
        state.update(status="resuming")
        self.queue_read(state, "resume")
        return self.mcp_instruction(state)

    def queue(self, state, phase, tool, arguments):
        state.update(mcp_phase=phase, mcp_request={"tool": MCP_PREFIX + tool, "arguments": arguments})
        state.pop("mcp_inflight", None)
        state.pop("mcp_announced", None)
        self.save(state)

    def queue_lookup(self, state, page=1):
        if page == 1:
            state["mcp_candidates"] = []
        self.queue(state, "lookup", "list_pull_requests", {
            **state["repository"], "head": state["repository"]["owner"] + ":" + state["branch"],
            "state": "all", "page": page, "perPage": 100})

    def queue_read(self, state, phase):
        self.queue(state, phase, "pull_request_read", {
            **state["repository"], "method": "get", "pullNumber": state["pr_number"]})

    def mcp_instruction(self, state):
        return ("Use the connected GitHub MCP server for the next call, with these exact arguments: " +
                json.dumps(state["mcp_request"], ensure_ascii=False) +
                ". PostToolUse will validate the response and supply the next step. "
                "Do not call gh, REST directly, merge, or report publication yet. "
                "If MCP is unavailable, record block --session " + self.session + " --reason with the concrete blocker.")

    def assert_pushed(self, state):
        worktree = self.assert_branch(state)
        if self.changed_files(worktree) or git(worktree, "rev-parse", "HEAD") != state["commit"]:
            raise FlowError("Task files or HEAD changed after push; preserve the result and inspect the task.")
        return worktree

    def pr_number(self, state, url):
        repo = state["repository"]
        prefix = f"https://github.com/{repo['owner']}/{repo['repo']}/pull/"
        if not isinstance(url, str) or not re.fullmatch(re.escape(prefix) + r"[1-9][0-9]*", url, re.I):
            raise FlowError("MCP returned a PR URL outside the task repository or an invalid PR URL.")
        return int(url.rsplit("/", 1)[1])

    def check_pr(self, state, pr, require_sha=True):
        if not isinstance(pr, dict):
            raise FlowError("MCP did not return a pull request object.")
        number = self.pr_number(state, pr.get("html_url"))
        if pr.get("number") != number:
            raise FlowError("PR number does not match its URL.")
        full_name = (state["repository"]["owner"] + "/" + state["repository"]["repo"]).lower()
        for side, branch in (("head", state["branch"]), ("base", state["base"])):
            ref = pr.get(side) or {}
            if ref.get("ref") != branch or (ref.get("repo") or {}).get("full_name", "").lower() != full_name:
                raise FlowError("MCP PR branch/repository does not match the task.")
        if require_sha and pr["head"].get("sha") != state["commit"]:
            raise FlowError("MCP PR head SHA does not match the tested and pushed commit.")
        return number

    def before_mcp(self, state, event):
        tool = event["tool_name"]
        inputs = event.get("tool_input", {})
        read_only = tool in (MCP_PREFIX + "get_me", MCP_PREFIX + "list_pull_requests", MCP_PREFIX + "pull_request_read")
        if not state or state["status"] not in ("awaiting_mcp", "resuming"):
            return {} if read_only else deny("GitHub MCP writes require a prepared and pushed task.")
        expected = state["mcp_request"]
        # Unrelated read-only GitHub calls may support investigation, but never
        # act as evidence for publication or unlock a write.
        if tool != expected["tool"] or json.dumps(inputs, sort_keys=True) != json.dumps(expected["arguments"], sort_keys=True):
            return {} if read_only else deny("Unexpected GitHub MCP write. " + self.mcp_instruction(state))
        if not event.get("tool_use_id"):
            return deny("MCP hook is missing tool_use_id; cannot correlate its response.")
        if state.get("mcp_inflight"):
            return deny("A GitHub MCP call is already pending. Await its result; after a lost response use retry to re-check GitHub.")
        self.assert_pushed(state)
        state["mcp_inflight"] = {"id": event["tool_use_id"], "request": expected}
        self.save(state)
        return {}

    def after_mcp(self, state, event):
        pending = state.get("mcp_inflight") if state else None
        if not pending or event.get("tool_use_id") != pending["id"]:
            return {}  # A stale, duplicate, unrelated, or other-session result.
        if (event.get("tool_name") != pending["request"]["tool"] or
                json.dumps(event.get("tool_input"), sort_keys=True) != json.dumps(pending["request"]["arguments"], sort_keys=True)):
            raise FlowError("MCP response does not match the registered call.")
        payload = mcp_payload(event.get("tool_response"))
        self.assert_pushed(state)
        phase = state["mcp_phase"]
        state.pop("mcp_inflight", None)
        if phase == "lookup":
            if not isinstance(payload, list) or len(payload) > 100:
                raise FlowError("MCP PR listing has an unexpected response shape.")
            previous_count = len(state["mcp_candidates"])
            for pr in payload:
                number = self.check_pr(state, pr, require_sha=False)
                if pr.get("state") not in ("open", "closed"):
                    raise FlowError("MCP PR listing is missing a valid state.")
                if number not in [item["number"] for item in state["mcp_candidates"]]:
                    state["mcp_candidates"].append({"number": number, "state": pr["state"]})
            if len(payload) == 100:
                if len(state["mcp_candidates"]) == previous_count:
                    raise FlowError("MCP pagination repeated the same PRs; cannot safely determine whether a PR exists.")
                self.queue_lookup(state, pending["request"]["arguments"]["page"] + 1)
            else:
                candidates = state.pop("mcp_candidates")
                opened = [pr for pr in candidates if pr["state"] == "open"]
                if len(opened) > 1 or (candidates and not opened):
                    raise FlowError("Task PR is closed/merged or ambiguous; start a new task after inspecting it.")
                if opened:
                    state["pr_number"] = opened[0]["number"]
                    self.queue_read(state, "inspect")
                else:
                    self.queue(state, "create", "create_pull_request", {
                        **state["repository"], "head": state["branch"], "base": state["base"],
                        "title": state["title"], "body": state["body"], "draft": state["draft"]})
        elif phase in ("create", "update"):
            if not isinstance(payload, dict):
                raise FlowError("MCP write result must contain the created/updated PR URL.")
            number = self.pr_number(state, payload.get("url") or payload.get("html_url"))
            if phase == "update" and number != state["pr_number"]:
                raise FlowError("MCP updated an unexpected PR.")
            state["pr_number"] = number
            self.queue_read(state, "verify")
        else:
            number = self.check_pr(state, payload)
            if number != state["pr_number"] or payload.get("state") != "open" or payload.get("merged") is not False:
                raise FlowError("The task PR is not open or does not match the requested PR.")
            if phase == "resume":
                state.update(status="active")
                for key in ("commit", "commit_tree", "commit_parent", "snapshot", "files", "pushed", "mcp_request", "mcp_phase"):
                    state.pop(key, None)
                self.save(state)
                return post_context("PR verified open. Continue review fixes in " + state["worktree"] + "; prepare again when finished.")
            matches = (payload.get("title") == state["title"] and payload.get("body", "") == state["body"]
                       and payload.get("draft") is state["draft"])
            if matches:
                state.update(status="published", pr=payload["html_url"], reported=False)
                state.pop("mcp_request", None)
                state.pop("mcp_phase", None)
                state.pop("error", None)
                self.save(state)
                return post_context("GitHub MCP verified publication: " + state["pr"] +
                                    ". Return the PR link and actual validation results in the final response.")
            if phase != "inspect":
                raise FlowError("MCP PR title/body/draft does not match the prepared result. Retry will inspect it before updating.")
            self.queue(state, "update", "update_pull_request", {
                **state["repository"], "pullNumber": number, "title": state["title"],
                "body": state["body"], "draft": state["draft"]})
        return post_context(self.mcp_instruction(state))

    def retry(self, state):
        if state["status"] != "blocked":
            raise FlowError("retry requires a blocked task.")
        state.pop("error", None)
        if state.get("mcp_phase") == "resume":
            state["status"] = "resuming"
            self.queue_read(state, "resume")
        elif state.get("pushed"):
            self.assert_pushed(state)
            state["status"] = "awaiting_mcp"
            self.queue_lookup(state)  # The last MCP write may have succeeded despite a lost response.
        else:
            state["status"] = "ready" if state.get("commit_tree") else ("active" if state.get("worktree") else "pending")
            self.save(state)
        return self.mcp_instruction(state) if state["status"] in ("awaiting_mcp", "resuming") else "Task status: " + state["status"]


def deny(message):
    return {"hookSpecificOutput": {"hookEventName": "PreToolUse", "permissionDecision": "deny",
                                   "permissionDecisionReason": message}}


def post_context(message):
    return {"hookSpecificOutput": {"hookEventName": "PostToolUse", "additionalContext": message}}


def hook(event):
    # A plan or interrupted/unknown lifecycle event must never publish anything.
    if event.get("permission_mode") == "plan":
        if event.get("hook_event_name") == "PreToolUse" and event.get("tool_name") in (
                MCP_PREFIX + "create_pull_request", MCP_PREFIX + "update_pull_request", MCP_PREFIX + "merge_pull_request"):
            return deny("Plan mode cannot publish or modify a GitHub PR.")
        return {}
    kind = event.get("hook_event_name")
    if kind not in ("SessionStart", "UserPromptSubmit", "PreToolUse", "PostToolUse", "Stop"):
        return {}
    flow = Flow(event["cwd"], event["session_id"])
    with flow.lock():
        state = flow.load()
        if kind in ("SessionStart", "UserPromptSubmit"):
            return (f"Git-flow hooks: session {flow.session}. Before a NEW editing task, run "
                    f"python scripts/codex_git_flow.py start --session {flow.session} --slug short-task-name. "
                    "The next tool call creates a branch/worktree and tells you its path; use that workdir and absolute edit paths. "
                    "For review fixes on the same published task use resume, not start. "
                    "Before finishing, inspect the diff and run prepare --session SESSION --manifest PATH. "
                    "Stop performs commit/push and supplies GitHub MCP calls for PR publication; execute them sequentially. "
                    "PostToolUse verifies MCP results. Read scripts/README.md for the manifest and MCP setup. "
                    "Do not manually commit/push, use gh or substitute REST calls. Questions/planning need no start. "
                    f"Current task: {json.dumps({key: state[key] for key in ('status', 'branch', 'worktree', 'pr', 'error') if key in state}, ensure_ascii=False) if state else 'none'}")
        if kind == "PostToolUse":
            if not event.get("tool_name", "").startswith(MCP_PREFIX):
                return {}
            try:
                return flow.after_mcp(state, event)
            except Exception as exc:
                if state:
                    state.update(status="blocked", error=str(exc))
                    flow.save(state)
                return post_context("GitHub MCP publication blocked: " + str(exc) +
                                    ". Preserve work and report the blocker; after resolving it use retry.")
        if kind == "PreToolUse":
            if state and state["status"] == "pending":
                try:
                    flow.activate(state)
                except Exception as exc:
                    state.update(status="blocked", error=str(exc))
                    flow.save(state)
                    return deny("Git-flow could not start: " + str(exc) + ". Fix access/setup, then use retry.")
                return deny("Task branch created. Retry your tool using workdir " + state["worktree"] +
                            " and absolute file paths inside it. The original worktree is unchanged.")
            tool = event.get("tool_name", "")
            if tool.startswith(MCP_PREFIX):
                try:
                    return flow.before_mcp(state, event)
                except FlowError as exc:
                    return deny(str(exc))
            inputs = event.get("tool_input", {})
            if not isinstance(inputs, dict):
                inputs = {"command": str(inputs)}
            text = json.dumps(inputs, ensure_ascii=False)
            if tool in ("Bash", "exec_command", "shell_command") and state and state["status"] in ("active", "ready", "awaiting_mcp", "resuming"):
                worktree = flow.assert_branch(state)
                cwd = Path(inputs.get("workdir") or inputs.get("cwd") or event["cwd"])
                if not cwd.resolve().is_relative_to(worktree):
                    return deny("Run task shell commands with workdir " + str(worktree) + ". Use absolute paths for edits.")
            edit = bool(re.search(r"apply_patch|^(Edit|Write)$|(?:write|edit|create|delete)_file|rename_refactoring", tool))
            if edit:
                if not state or state["status"] != "active":
                    return deny("Start/resume the editing task before editing. A ready task must be reopened with edit first.")
                worktree = flow.assert_branch(state)
                paths = [str(value) for key, value in inputs.items() if key in ("file_path", "path", "source_path", "target_path")]
                if "apply_patch" in tool or tool in ("Edit", "Write"):
                    paths += re.findall(r"\*\*\* (?:Add File|Update File|Delete File|Move to): (.+)", inputs.get("command", inputs.get("patch", "")))
                cwd = Path(inputs.get("workdir") or inputs.get("cwd") or event["cwd"])
                if not paths and not cwd.resolve().is_relative_to(worktree):
                    return deny("Use absolute edit paths in the task worktree: " + str(worktree))
                for name in paths:
                    path = Path(name)
                    if not path.is_absolute():
                        path = cwd / path
                    if not path.resolve().is_relative_to(worktree):
                        return deny("Edit targets a path outside the task worktree: " + name)
            # A convenience guard, not a shell parser or a security boundary.
            if tool in ("Bash", "exec_command", "shell_command") and re.search(
                    r"\bgit\s+(?:(?:commit|push|merge|reset|checkout|switch)(?:\s|$)|worktree\s+(?:add|remove|move|prune|repair)\b)|\bgh\s+(?:pr|api)\b",
                    str(inputs.get("command", inputs.get("cmd", text))), re.I):
                return deny("Let hooks manage local Git and use their GitHub MCP requests for PRs. Use start/prepare/resume/retry.")
            return {}
        if not state or state["status"] == "cancelled":
            return {}
        if state["status"] == "ready":
            try:
                instruction = flow.publish(state)
                state["mcp_announced"] = True
                flow.save(state)
                return {"decision": "block", "reason": instruction}
            except Exception as exc:
                state.update(status="blocked", error=str(exc))
                flow.save(state)
                return {"decision": "block", "reason": "Git-flow publication blocked: " + str(exc) +
                        ". Preserve work and report the blocker. Use retry only after resolving it; do not claim success."}
        if state["status"] in ("awaiting_mcp", "resuming"):
            if state.get("mcp_inflight") or (state.get("mcp_announced") and event.get("stop_hook_active")):
                state.update(status="blocked", error="GitHub MCP did not complete the requested step. "
                             "Check the github server connection and tool result; retry will re-read GitHub before writing.")
                flow.save(state)
                return {"decision": "block", "reason": state["error"] + " Report this blocker; do not claim publication succeeded."}
            state["mcp_announced"] = True
            flow.save(state)
            return {"decision": "block", "reason": flow.mcp_instruction(state)}
        if state["status"] == "published":
            if state.get("reported"):
                return {}
            if state["pr"] not in (event.get("last_assistant_message") or "") and not event.get("stop_hook_active"):
                return {"decision": "block", "reason": "Include the task PR in the final answer: " + state["pr"]}
            state["reported"] = True
            flow.save(state)
            return {}
        if state["status"] == "blocked":
            return {"systemMessage": "Git-flow blocked: " + state.get("error", "unknown error")}
        if not event.get("stop_hook_active"):
            return {"decision": "block", "reason": "Git-flow task is unfinished. Prepare the reviewed result for publication, "
                    "or use block --reason with the concrete blocker / need for user input. Do not publish unfinished work."}
        return {"systemMessage": "Git-flow task remains unfinished; no PR was published."}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("hook", "start", "prepare", "status", "resume", "edit", "retry", "block", "cancel"))
    parser.add_argument("--session")
    parser.add_argument("--slug")
    parser.add_argument("--manifest", type=Path)
    parser.add_argument("--reason")
    args = parser.parse_args()
    if args.action == "hook":
        event = json.load(sys.stdin)
        try:
            result = hook(event)
        except Exception as exc:
            message = "Git-flow hook failed: " + str(exc)
            result = deny(message) if event.get("hook_event_name") == "PreToolUse" else {"systemMessage": message}
        # Stop hooks run concurrently in Codex. Invoke the existing notification
        # here, only after the Git-flow handler allows this response to finish.
        if event.get("hook_event_name") == "Stop" and isinstance(result, dict) and result.get("decision") != "block":
            notification = Path(__file__).with_name("notify-codex-finished.ps1")
            if os.name == "nt" and notification.exists():
                try:
                    notice = json.loads(run(notification.parent, "powershell.exe", "-NoProfile", "-NonInteractive",
                                            "-WindowStyle", "Hidden", "-File", str(notification), timeout=15))
                    if notice.get("systemMessage"):
                        result["systemMessage"] = (result.get("systemMessage", "") + " " + notice["systemMessage"]).strip()
                except Exception as exc:
                    result["systemMessage"] = (result.get("systemMessage", "") + " Notification failed: " + str(exc)).strip()
        print(result if isinstance(result, str) else json.dumps(result, ensure_ascii=False))
        return
    flow = Flow(Path.cwd(), args.session)
    with flow.lock():
        if args.action == "start":
            result = flow.request_start(args.slug)
        elif args.action == "prepare":
            if not args.manifest:
                raise FlowError("--manifest is required")
            result = flow.prepare(read_json(args.manifest))
        elif args.action == "resume":
            result = flow.resume()
        elif args.action == "status":
            result = json.dumps(flow.load(), ensure_ascii=False, indent=2)
        elif args.action == "retry":
            state = flow.load()
            if not state:
                raise FlowError("No task is registered for this session.")
            result = flow.retry(state)
        else:
            state = flow.load()
            if not state:
                raise FlowError("No task is registered for this session.")
            if args.action in ("block", "cancel"):
                if not args.reason:
                    raise FlowError("--reason is required; cancel is only for an explicitly cancelled task.")
                state.update(status="blocked" if args.action == "block" else "cancelled", error=args.reason)
            elif args.action == "edit":
                if state["status"] != "ready" or state.get("commit"):
                    raise FlowError("edit requires a prepared task that has not been committed.")
                state["status"] = "active"
            flow.save(state)
            result = "Task status: " + state["status"]
        print(result)


if __name__ == "__main__":
    sys.stdin.reconfigure(encoding="utf-8")
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    try:
        main()
    except (FlowError, OSError, ValueError, subprocess.TimeoutExpired) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
