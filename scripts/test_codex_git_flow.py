"""Real local Git and GitHub MCP hook events; no live GitHub writes or credentials."""

import json
import copy
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import codex_git_flow as flow


class GitFlowTests(unittest.TestCase):
    def setUp(self):
        directory = Path(__file__).resolve().parent.parent / "build" / "git-flow-tests"
        directory.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(prefix="case-", dir=directory)
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.repo = self.directory / "repository with spaces"
        self.repo.mkdir()
        self.remote = self.directory / "origin.git"
        environment = patch.dict(os.environ, {
            "GIT_CONFIG_GLOBAL": os.devnull, "GIT_CONFIG_NOSYSTEM": "1",
            "GIT_AUTHOR_NAME": "Flow Test", "GIT_AUTHOR_EMAIL": "flow@example.invalid",
            "GIT_COMMITTER_NAME": "Flow Test", "GIT_COMMITTER_EMAIL": "flow@example.invalid",
        })
        environment.start()
        self.addCleanup(environment.stop)
        self.git("init", "-b", "main")
        self.git("config", "core.autocrlf", "true")
        flow.git(self.directory, "init", "--bare", "--initial-branch=main", str(self.remote))
        self.git("remote", "add", "origin", str(self.remote))
        files = {
            ".gitignore": ".codex/*\n!.codex/hooks.json\n!.codex/config.toml\n.codex-work/\nbuild/\n",
            ".codex/hooks.json": "{}\n", ".codex/config.toml": "# fixture\n", "AGENTS.md": "Test instructions\n",
            "scripts/codex_git_flow.py": Path(flow.__file__).read_text(encoding="utf-8"), "note.md": "Original\n",
        }
        for name, text in files.items():
            path = self.repo / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")
        self.git("add", "--", *files)
        self.git("commit", "-m", "chore: fixture")
        self.git("push", "-u", "origin", "main")
        self.engine = flow.Flow(self.repo, "test-session")
        self.prs = []
        self.mcp_calls = []
        self.call_sequence = 0
        original_repository = flow.repository_from_origin
        repository = patch.object(flow, "repository_from_origin", side_effect=lambda origin:
                                  {"owner": "example", "repo": "task-tracker"} if origin == str(self.remote)
                                  else original_repository(origin))
        repository.start()
        self.addCleanup(repository.stop)
        original_run = flow.run

        def fake_run(cwd, *argv, **kwargs):
            self.assertNotEqual("gh", argv[0], "The MCP workflow must not invoke GitHub CLI")
            return original_run(cwd, *argv, **kwargs)

        self.fake_run = fake_run
        self.mock = patch.object(flow, "run", side_effect=fake_run)
        self.mock.start()
        self.addCleanup(self.mock.stop)

    def git(self, *args):
        return flow.git(self.repo, *args)

    def event(self, kind, **extra):
        return {"hook_event_name": kind, "session_id": "test-session", "cwd": str(self.repo),
                "permission_mode": "default", **extra}

    def start(self):
        self.engine.request_start("test-task")
        result = flow.hook(self.event("PreToolUse", tool_name="Bash", tool_input={"command": "git status"}))
        self.assertEqual("deny", result["hookSpecificOutput"]["permissionDecision"])
        self.worktree = Path(self.engine.load()["worktree"])
        return self.worktree

    def prepare(self, **extra):
        (self.worktree / "note.md").write_text("Task result\n", encoding="utf-8")
        self.engine.prepare({"title": "docs: update note", "body": "Describe the task result.",
                             "files": ["note.md"], **extra})

    def publish(self):
        result = flow.hook(self.event("Stop", last_assistant_message="Done"))
        self.drain_mcp()
        return result

    def fake_mcp(self, tool, arguments):
        """Fixtures follow official MinimalPullRequest / MinimalResponse wire shapes."""
        self.mcp_calls.append((tool, arguments))
        heads = {}
        for pr in self.prs:
            branch = pr["head"]["ref"]
            if branch not in heads:
                heads[branch] = flow.git(self.remote, "rev-parse", "refs/heads/" + branch)
            pr["head"]["sha"] = heads[branch]
        if tool == flow.MCP_PREFIX + "list_pull_requests":
            matches = [pr for pr in self.prs if "example:" + pr["head"]["ref"] == arguments["head"]]
            start = (arguments["page"] - 1) * arguments["perPage"]
            return matches[start:start + arguments["perPage"]]
        if tool == flow.MCP_PREFIX + "create_pull_request":
            number = len(self.prs) + 1
            pr = {"number": number, "html_url": f"https://github.com/example/task-tracker/pull/{number}",
                  "state": "open", "merged": False, "draft": arguments["draft"],
                  "title": arguments["title"], "body": arguments["body"],
                  "head": {"ref": arguments["head"], "sha": flow.git(self.remote, "rev-parse", "refs/heads/" + arguments["head"]),
                           "repo": {"full_name": "example/task-tracker"}},
                  "base": {"ref": arguments["base"], "repo": {"full_name": "example/task-tracker"}}}
            self.prs.append(pr)
            return {"id": str(1000 + number), "url": pr["html_url"]}
        pr = next(pr for pr in self.prs if pr["number"] == arguments["pullNumber"])
        if tool == flow.MCP_PREFIX + "update_pull_request":
            pr.update({key: arguments[key] for key in ("title", "body", "draft")})
            return {"id": str(1000 + pr["number"]), "url": pr["html_url"]}
        self.assertEqual(flow.MCP_PREFIX + "pull_request_read", tool)
        self.assertEqual("get", arguments["method"])
        return pr

    def next_mcp(self, response=None, lose_response=False):
        request = self.engine.load()["mcp_request"]
        self.call_sequence += 1
        fields = {"tool_name": request["tool"], "tool_input": request["arguments"], "tool_use_id": f"call-{self.call_sequence}"}
        before = flow.hook(self.event("PreToolUse", **fields))
        self.assertEqual({}, before)
        if response is None:
            payload = self.fake_mcp(request["tool"], request["arguments"])
            response = {"content": [{"type": "text", "text": json.dumps(payload, ensure_ascii=False)}], "isError": False}
        if lose_response:
            return fields
        self.last_mcp_result = flow.hook(self.event("PostToolUse", **fields, tool_response=response))
        return self.last_mcp_result

    def drain_mcp(self):
        for _ in range(10):
            if self.engine.load()["status"] not in ("awaiting_mcp", "resuming"):
                return
            self.next_mcp()
        self.fail("MCP workflow did not finish in 10 calls")

    def resume(self):
        self.engine.resume()
        self.drain_mcp()

    def test_question_and_plan_do_not_create_branch_or_publish(self):
        self.assertEqual({}, flow.hook(self.event("Stop")))
        self.engine.request_start("planned-task")
        self.assertEqual({}, flow.hook(self.event("PreToolUse", permission_mode="plan")))
        self.assertEqual("pending", self.engine.load()["status"])
        self.assertEqual({}, flow.hook(self.event("Stop", permission_mode="plan")))
        self.assertEqual("main", self.git("branch", "--show-current"))
        self.assertEqual([], self.mcp_calls)

    def test_dirty_original_is_preserved_and_edits_are_routed(self):
        (self.repo / "note.md").write_text("User work\n", encoding="utf-8")
        self.git("add", "note.md")
        staged = self.git("diff", "--cached")
        self.start()
        self.assertEqual(staged, self.git("diff", "--cached"))
        self.assertEqual("main", self.git("branch", "--show-current"))
        bad = flow.hook(self.event("PreToolUse", tool_name="apply_patch", tool_input={
            "command": "*** Update File: " + str(self.repo / "note.md")}))
        self.assertEqual("deny", bad["hookSpecificOutput"]["permissionDecision"])
        good = flow.hook(self.event("PreToolUse", tool_name="apply_patch", tool_input={
            "command": "*** Update File: " + str(self.worktree / "note.md")}))
        self.assertEqual({}, good)
        shell = flow.hook(self.event("PreToolUse", tool_name="Bash", tool_input={"command": "Get-Content note.md"}))
        self.assertEqual("deny", shell["hookSpecificOutput"]["permissionDecision"])

    def test_same_slug_uses_new_branch_and_session_isolation(self):
        self.start()
        second = flow.Flow(self.repo, "second-session")
        second.request_start("test-task")
        flow.hook(self.event("PreToolUse", session_id="second-session"))
        self.assertEqual("codex/test-task-2", second.load()["branch"])
        self.assertEqual("codex/test-task", self.engine.load()["branch"])

    def test_stop_commits_pushes_and_creates_only_one_pr(self):
        self.start()
        self.prepare()
        result = self.publish()
        state = self.engine.load()
        self.assertEqual("published", state["status"])
        self.assertIn("list_pull_requests", result["reason"])
        self.assertIn(state["pr"], self.last_mcp_result["hookSpecificOutput"]["additionalContext"])
        self.assertEqual("1", flow.git(self.worktree, "rev-list", "--count", "origin/main..HEAD"))
        self.assertEqual(state["commit"], flow.git(self.remote, "rev-parse", "refs/heads/codex/test-task"))
        flow.hook(self.event("Stop", last_assistant_message=state["pr"], stop_hook_active=True))
        self.assertEqual(1, sum(tool.endswith("__create_pull_request") for tool, _ in self.mcp_calls))
        self.assertEqual({}, flow.hook(self.event("Stop", last_assistant_message="Answer to a later learning question")))

    def test_post_validation_changes_are_not_committed(self):
        self.start()
        self.prepare()
        (self.worktree / "note.md").write_text("Changed after check\n", encoding="utf-8")
        self.publish()
        self.assertEqual("blocked", self.engine.load()["status"])
        self.assertEqual("0", flow.git(self.worktree, "rev-list", "--count", "origin/main..HEAD"))
        self.assertEqual([], self.mcp_calls)

    def test_unlisted_files_prevent_preparation(self):
        self.start()
        (self.worktree / "unrelated.md").write_text("Not reviewed\n", encoding="utf-8")
        with self.assertRaisesRegex(flow.FlowError, "match all changed"):
            self.prepare()

    def test_failed_checks_require_external_blocker_for_draft(self):
        self.start()
        checks = [[flow.sys.executable, "-c", "raise SystemExit(1)"]]
        with self.assertRaisesRegex(flow.FlowError, "Validation failed"):
            self.prepare(checks=checks)
        self.prepare(checks=checks, external_blocker="Fixture simulates an unavailable external service.")
        self.publish()
        self.assertTrue(self.prs[0]["draft"])
        self.assertIn("FAIL:", self.engine.load()["body"])

    def test_retry_after_mcp_auth_failure_does_not_duplicate_commit(self):
        self.start()
        self.prepare()
        flow.hook(self.event("Stop"))
        self.next_mcp(response={"isError": True, "content": [{"type": "text", "text": "HTTP 401"}]})
        state = self.engine.load()
        self.assertEqual("blocked", state["status"])
        commit = state["commit"]
        self.engine.retry(state)
        self.drain_mcp()
        self.assertEqual("published", self.engine.load()["status"])
        self.assertEqual(commit, self.engine.load()["commit"])

    def test_lost_commit_response_is_recovered_without_new_commit(self):
        self.start()
        self.prepare()

        def interrupted_commit(cwd, *argv, **kwargs):
            output = self.fake_run(cwd, *argv, **kwargs)
            if argv[:2] == ("git", "commit"):
                raise flow.FlowError("Simulated lost commit response")
            return output

        with patch.object(flow, "run", side_effect=interrupted_commit):
            self.publish()
        state = self.engine.load()
        self.assertEqual("blocked", state["status"])
        self.assertNotIn("commit", state)
        self.assertIn("commit_tree", state)
        committed = flow.git(self.worktree, "rev-parse", "HEAD")
        state["status"] = "ready"
        self.engine.save(state)
        self.publish()
        self.assertEqual("published", self.engine.load()["status"])
        self.assertEqual(committed, self.engine.load()["commit"])

    def test_followup_updates_existing_pr_and_refuses_closed_pr(self):
        self.start()
        self.prepare()
        self.publish()
        self.resume()
        (self.worktree / "note.md").write_text("Review fix\n", encoding="utf-8")
        self.engine.prepare({"title": "docs: address review", "body": "Review fix", "files": ["note.md"]})
        self.publish()
        self.assertEqual(1, len(self.prs))
        self.assertTrue(any(tool.endswith("__update_pull_request") for tool, _ in self.mcp_calls))
        self.prs[0].update(state="closed", merged=True)
        self.resume()
        self.assertEqual("blocked", self.engine.load()["status"])

    def test_stop_without_preparation_does_not_loop_or_commit(self):
        self.start()
        self.assertEqual("block", self.publish()["decision"])
        self.assertNotIn("decision", flow.hook(self.event("Stop", stop_hook_active=True)))
        self.assertEqual([], self.mcp_calls)

    def test_rename_and_unicode_paths_are_committed(self):
        self.start()
        flow.git(self.worktree, "mv", "note.md", "заметка с пробелом.md")
        self.engine.prepare({"title": "docs: rename note", "body": "Rename", "files": ["note.md", "заметка с пробелом.md"]})
        self.publish()
        self.assertEqual("published", self.engine.load()["status"])

    def test_invalid_slug_and_missing_setup_are_rejected(self):
        with self.assertRaises(flow.FlowError):
            self.engine.request_start("../escape")
        self.git("rm", "AGENTS.md")
        self.git("commit", "-m", "chore: remove setup")
        self.git("push", "origin", "main")
        self.engine.request_start("missing-setup")
        flow.hook(self.event("PreToolUse"))
        self.assertEqual("blocked", self.engine.load()["status"])
        self.assertNotIn("worktree", self.engine.load())

    @unittest.skipUnless(os.name == "nt", "Exercises the project's Windows hook launcher")
    def test_actual_hook_command_reads_stdin_and_resolves_subdirectory(self):
        config = flow.read_json(Path(__file__).resolve().parent.parent / ".codex" / "hooks.json")
        command = config["hooks"]["PreToolUse"][0]["hooks"][0]["command"]
        event = self.event("PreToolUse", tool_name="apply_patch", tool_input={"command": "*** Update File: note.md"})
        event["cwd"] = str(self.repo / "scripts")
        result = subprocess.run(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command],
                                cwd=self.repo / "scripts", input=json.dumps(event), capture_output=True,
                                encoding="utf-8", timeout=30)
        self.assertEqual(0, result.returncode, result.stderr)
        response = json.loads(result.stdout)
        self.assertEqual("deny", response["hookSpecificOutput"]["permissionDecision"])

    def test_required_java_checks_cannot_be_omitted(self):
        self.start()
        source = self.worktree / "src" / "main" / "Example.java"
        source.parent.mkdir(parents=True)
        source.write_text("class Example {}\n", encoding="utf-8")
        calls = []

        def java_check(cwd, *argv, **kwargs):
            if str(argv[0]).endswith(("gradlew", "gradlew.bat")):
                calls.append(argv)
                return "Tests passed (fixture)"
            return self.fake_run(cwd, *argv, **kwargs)

        with patch.object(flow, "run", side_effect=java_check):
            self.engine.prepare({"title": "feat: add example", "body": "Example", "files": ["src/main/Example.java"]})
        self.assertEqual(1, len(calls))
        self.assertEqual("test", calls[0][1])

    def test_existing_draft_becomes_ready_after_successful_review_fix(self):
        self.start()
        self.prepare(external_blocker="Service unavailable")
        self.publish()
        self.assertTrue(self.prs[0]["draft"])
        self.resume()
        (self.worktree / "note.md").write_text("Verified review fix\n", encoding="utf-8")
        self.engine.prepare({"title": "docs: verify note", "body": "Verified", "files": ["note.md"]})
        self.publish()
        self.assertFalse(self.prs[0]["draft"])

    def test_mcp_writes_require_push_lookup_and_exact_manifest_arguments(self):
        rejected = flow.hook(self.event("PreToolUse", tool_name=flow.MCP_PREFIX + "create_pull_request", tool_input={}))
        self.assertEqual("deny", rejected["hookSpecificOutput"]["permissionDecision"])
        self.start()
        self.prepare()
        flow.hook(self.event("Stop"))
        rejected = flow.hook(self.event("PreToolUse", tool_name=flow.MCP_PREFIX + "create_pull_request", tool_input={}))
        self.assertEqual("deny", rejected["hookSpecificOutput"]["permissionDecision"])
        self.next_mcp()  # Only now is a create allowed.
        request = self.engine.load()["mcp_request"]
        for changed in ({"base": "wrong"}, {"draft": 0}, {"reviewers": ["someone"]}):
            rejected = flow.hook(self.event("PreToolUse", tool_name=request["tool"],
                                tool_input={**request["arguments"], **changed}, tool_use_id="bad-write"))
            self.assertEqual("deny", rejected["hookSpecificOutput"]["permissionDecision"])
        self.assertEqual([], self.prs)

    def test_creation_url_is_not_enough_and_wrong_sha_cannot_complete(self):
        self.start()
        self.prepare()
        flow.hook(self.event("Stop"))
        self.next_mcp()  # list
        self.next_mcp()  # create returns only {id,url}
        self.assertEqual("awaiting_mcp", self.engine.load()["status"])
        self.assertEqual("verify", self.engine.load()["mcp_phase"])
        bad_pr = copy.deepcopy(self.prs[0])
        bad_pr["head"]["sha"] = "0" * 40
        self.next_mcp(response={"structuredContent": bad_pr})
        self.assertEqual("blocked", self.engine.load()["status"])
        self.assertIn("SHA", self.engine.load()["error"])

    def test_lost_create_response_recovers_existing_pr_without_duplicate(self):
        self.start()
        self.prepare()
        flow.hook(self.event("Stop"))
        self.next_mcp()
        self.next_mcp(lose_response=True)
        self.assertEqual(1, len(self.prs))
        flow.hook(self.event("Stop", stop_hook_active=True))
        self.assertEqual("blocked", self.engine.load()["status"])
        self.engine.retry(self.engine.load())
        self.drain_mcp()
        self.assertEqual("published", self.engine.load()["status"])
        self.assertEqual(1, sum(tool.endswith("__create_pull_request") for tool, _ in self.mcp_calls))
        self.assertEqual("1", flow.git(self.worktree, "rev-list", "--count", "origin/main..HEAD"))

    def test_stale_duplicate_and_other_session_mcp_responses_are_ignored(self):
        self.start()
        self.prepare()
        flow.hook(self.event("Stop"))
        fields = self.next_mcp(lose_response=True)
        response = {"content": [{"type": "text", "text": "[]"}]}
        stale = {**fields, "tool_use_id": "stale-call"}
        self.assertEqual({}, flow.hook(self.event("PostToolUse", **stale, tool_response=response)))
        self.assertEqual({}, flow.hook(self.event("PostToolUse", **fields, session_id="other", tool_response=response)))
        self.assertEqual("lookup", self.engine.load()["mcp_phase"])
        flow.hook(self.event("PostToolUse", **fields, tool_response=response))
        self.assertEqual("create", self.engine.load()["mcp_phase"])
        self.assertEqual({}, flow.hook(self.event("PostToolUse", **fields, tool_response=response)))

    def test_missing_mcp_connection_stops_without_false_success_or_loop(self):
        self.start()
        self.prepare()
        flow.hook(self.event("Stop"))
        result = flow.hook(self.event("Stop", stop_hook_active=True))
        self.assertEqual("block", result["decision"])
        self.assertEqual("blocked", self.engine.load()["status"])
        self.assertNotIn("decision", flow.hook(self.event("Stop", stop_hook_active=True)))
        self.assertEqual([], self.prs)

    def test_existing_pr_lookup_consumes_all_pages(self):
        self.start()
        self.prepare()
        flow.hook(self.event("Stop"))
        state = self.engine.load()
        self.fake_mcp(flow.MCP_PREFIX + "create_pull_request", {
            "head": state["branch"], "base": state["base"], "title": state["title"], "body": state["body"], "draft": False})
        template = self.prs[0]
        self.prs = [copy.deepcopy(template) for _ in range(101)]
        for index, pr in enumerate(self.prs, 1):
            pr.update(number=index, html_url=f"https://github.com/example/task-tracker/pull/{index}",
                      state="closed" if index < 101 else "open")
        self.drain_mcp()
        self.assertEqual("published", self.engine.load()["status"])
        self.assertEqual(101, self.engine.load()["pr_number"])
        self.assertEqual([1, 2], [args["page"] for tool, args in self.mcp_calls if tool.endswith("__list_pull_requests")])


class McpContractTests(unittest.TestCase):
    def test_supported_origins_and_rejected_non_github_origins(self):
        for origin in ("https://github.com/Owner/repo.git", "git@github.com:Owner/repo.git", "ssh://git@github.com/Owner/repo"):
            self.assertEqual({"owner": "Owner", "repo": "repo"}, flow.repository_from_origin(origin))
        for origin in ("https://example.com/Owner/repo", "C:/origin.git", "https://user:secret@github.com/Owner/repo"):
            with self.assertRaises(flow.FlowError):
                flow.repository_from_origin(origin)

    def test_mcp_response_decoding_rejects_errors_and_unknown_shapes(self):
        self.assertEqual([], flow.mcp_payload({"content": [{"type": "text", "text": "[]"}]}))
        self.assertEqual({"number": 1}, flow.mcp_payload({"structuredContent": {"number": 1}}))
        for response in (None, {"isError": True}, {"content": []}, {"content": [{"type": "text", "text": "Not JSON"}]}):
            with self.assertRaises(flow.FlowError):
                flow.mcp_payload(response)

    def test_plan_mode_denies_mcp_writes_without_state_changes(self):
        response = flow.hook({"permission_mode": "plan", "hook_event_name": "PreToolUse",
                              "tool_name": flow.MCP_PREFIX + "create_pull_request"})
        self.assertEqual("deny", response["hookSpecificOutput"]["permissionDecision"])


if __name__ == "__main__":
    unittest.main()
