# INCIDENT — CI on `main` is red. Fix before continuing.

**Status:** Sprint 4 Day 30 is BLOCKED until this is resolved.
**Severity:** P0 by the project's own rule: *"A red `main` is an emergency
that gets fixed before anything else."* (Sprint 1 closing rule, repeated
every sprint close.)

---

## What's known

Screenshot from `github.com/NikunjS91/Sentinel/actions/runs/27311204770`
shows CI run **#43** for PR **#19** — the Sprint 2 close PR
(`chore/S2-D10-sprint-close`):

- ✅ `orchestrator (Java)` — passed, 2m 26s
- ❌ **`agents (Python)` — FAILED, exit code 1, 58s**
- ✅ `demo-app (Java)` — passed, 24s

That PR was reported as "merged to main, sprint-2 tag pushed" in the
Day-26 message. If the merge actually happened with the agents job red,
the project has been operating with a **broken `main` for the entire
duration of Sprint 3 and the first four days of Sprint 4**. Every
"all tests green" status since has been masking this.

---

## Step 1 — Establish ground truth (do this FIRST)

Before fixing anything, find out the actual state of `main`. Run these
in order and capture the output.

```bash
# 1. What does main look like right now?
git fetch origin
git log origin/main --oneline -20

# 2. Where do the sprint tags actually point?
git show sprint-2 --stat | head -5
git show sprint-3 --stat | head -5

# 3. Is the latest CI run on main green or red?
# Open this URL and check the most recent run for branch=main:
#   https://github.com/NikunjS91/Sentinel/actions?query=branch%3Amain
```

**Report back what you find before doing anything else.** The fix path
depends entirely on whether `main` has actually been red the whole time
or whether the failure was on a transient branch that got rebased away.

Three possibilities and their implications:

### Case A — `main`'s latest CI run is GREEN
The screenshot is stale. PR #19 went red on one run; a subsequent
rebase/re-run/follow-up PR landed green. Everything since has been fine.
**Action: just confirm by running the CI again on a fresh branch and
move on.** No emergency.

### Case B — `main`'s latest CI run is RED
This is the real emergency. `main` has been red since at least Sprint 2
close. Every PR since (Day 21 through Day 29) has been merging into a
broken `main`. Local `mvn verify` and `pytest` have been the only signal.
**Action: stop Sprint 4 work. Fix the failing job. Verify green. Then
resume.**

### Case C — Tags exist but aren't on `main`
The `sprint-2` and `sprint-3` tags may be on commits that were never
merged. **Action: figure out what `main` actually contains, and either
move the tags or fast-forward `main` to what should be there.**

**Do not skip Step 1.** Knowing which case we're in determines
everything else.

---

## Step 2 — Read the actual failure

If Case B (or any case where the agents Python job is genuinely failing
right now), get the pytest output that produced exit code 1.

Click into the failed `agents (Python)` job for the most recent red run
on main. Look at the "Test" step (or whatever step ran `pytest`). Copy
the output — specifically:

- The summary line at the end (`X failed, Y passed in Zs`).
- The names of the failing tests.
- The first 30 lines of one failing test's output (error type, traceback,
  assertion message).

**Paste that here so we can diagnose the actual cause.** Without the
failure text, the fix is a guess.

If you cannot reach the old run logs (GitHub Actions logs expire), the
alternative is to reproduce the failure: trigger a new CI run on a
fresh branch and read the failure from there.

```bash
git checkout main && git pull
git checkout -b fix/ci-main-green
# Make a trivial change to force CI:
echo "" >> README.md
git add README.md
git commit -m "fix: trigger CI to reproduce failure"
git push -u origin fix/ci-main-green
# Open the PR, watch the CI, capture the failure.
```

---

## Step 3 — Hypotheses about what's failing

Without the actual log, here are the most likely causes ranked by
probability — based on what changed in the Sprint 2 close PR and the
fact that the failure is specifically the Python suite.

### Hypothesis 1 (most likely) — `test_swarm_asymmetry.py` fails in CI

The Sprint 2 close added `test_swarm_asymmetry.py` (TC-2.11.2). It
constructs a real `PromptRegistry(settings.prompts_dir)` and calls real
agent functions with a fake LLM. **Two ways this can fail in CI but
pass locally:**

- **Path resolution.** `settings.prompts_dir` defaults to
  `Path(__file__).parent / "prompts"`. The resolution depends on the
  working directory pytest was launched from. CI may launch from
  `agents/` while locally you launch from project root, or vice versa.
- **Async fixtures.** The test uses `@pytest.mark.asyncio` and
  `monkeypatch.setattr(settings, "tool_mode", "fixture")`. If
  `pytest-asyncio` mode is `strict` in CI's `pyproject.toml` but
  `auto` locally (or vice versa), the test may not run as a coroutine.

### Hypothesis 2 — `mypy --strict` regression

You've been holding mypy strict clean for 28 days. If any recent PR
slipped a type annotation, CI catches it where local development
sometimes doesn't (different mypy version). The Sprint 2 close added
new imports for the asymmetry test; one of those could have a missing
return-type annotation.

### Hypothesis 3 — `ruff check .` regression

Same shape as #2 but for ruff. Less likely because ruff is fast and
usually fails locally too — but possible if rule sets drift between
versions.

### Hypothesis 4 — Test that needs network access

Some test added during Sprint 2 close may attempt a network call that
works on your laptop (Loki / Prometheus / Postgres running in Compose)
but fails in CI (none of those running). The skip-guard pattern was
established for exactly this; if a test was added without the guard,
it fails in CI.

### Hypothesis 5 — Sprint 3+ changes broke a Sprint 2 contract

If `main` *has* been red since Sprint 2 close and every PR since has
been merging into red, one of the recent PRs may have *fixed* the
original Sprint 2 issue but introduced a different one. The most
recent CI run is the source of truth, not run #43.

---

## Step 4 — Fix sequence based on the actual failure

**Don't do this part until Steps 1 and 2 are done.** Below is the
playbook for each hypothesis; pick the branch that matches the actual
log.

### If Hypothesis 1 (test_swarm_asymmetry path or async setup)

Locally, reproduce CI's invocation exactly:

```bash
cd agents
# CI most likely runs: cd agents && pip install -e ".[dev]" && pytest
# Run pytest the exact same way:
pytest -v
```

If it fails locally with that invocation, you've reproduced the bug.
Diagnose by:

```bash
# Run JUST the asymmetry test verbosely
pytest -v -s app/tests/test_swarm_asymmetry.py

# If "fixture 'X' not found" — async config issue
# If "FileNotFoundError" — path issue
# If "RuntimeError: coroutine was never awaited" — pytest-asyncio mode mismatch
```

**Path fix:** make `Settings.prompts_dir` resolve absolutely regardless
of CWD:

```python
# agents/app/settings.py
class Settings(BaseSettings):
    prompts_dir: Path = Path(__file__).resolve().parent / "prompts"
    #                              ^^^^^^^^^^ add .resolve()
```

**Async mode fix:** pin `pytest-asyncio` mode in `pyproject.toml`:

```toml
[tool.pytest.ini_options]
asyncio_mode = "auto"
```

(If `auto` mode causes other tests to misbehave, use `strict` and add
`@pytest.mark.asyncio` decorators where needed. Pick one and apply
consistently.)

### If Hypothesis 2 (mypy strict regression)

```bash
cd agents
mypy --strict .
```

Look for any line that says `error:` in red. Most likely candidates:
- A new function returning `Any` implicitly.
- A `Callable` type alias that doesn't match the registered agent.
- An async function that returns `None` but doesn't declare it.

Fix the annotation. Run again. Repeat until clean.

### If Hypothesis 3 (ruff regression)

```bash
cd agents
ruff check .
```

Apply the fix it suggests (often `ruff check . --fix` resolves it
automatically) — *except* the `BLE001` suppressions which are
deliberate (Day 14 / Day 15 lesson).

### If Hypothesis 4 (network access in CI)

Find the test that's actually trying to reach a network service in CI.
The fix is the skip-guard pattern (Days 8 / 14 / 15):

```python
async def _service_reachable() -> bool:
    try:
        async with httpx.AsyncClient(timeout=2.0) as c:
            await c.get(f"{settings.SOME_URL}/health")
            return True
    except Exception:
        return False

@pytest.mark.skipif(not _service_reachable(),
                    reason="service not available in CI")
async def test_that_needs_service():
    ...
```

### If Hypothesis 5 (a Sprint-3/4 PR broke something)

`git bisect` is the right tool. Find the most recent green CI run on
main, find the oldest red run on main, bisect between them:

```bash
git bisect start
git bisect bad main                          # current main is red
git bisect good <last-known-green-sha>       # the sha that was green
# git checks out the midpoint commit
cd agents && pytest                          # run the failing tests
# Tell bisect:
git bisect good     # if tests passed
git bisect bad      # if tests failed
# Repeat until git names the offending commit
git bisect reset
```

This is the cleanest way to identify exactly which PR broke things.

---

## Step 5 — Verify the fix end to end

Once you have a hypothesis-matching fix:

```bash
# 1. Run the Python suite from the SAME directory CI does
cd agents
pip install -e ".[dev]"     # in case CI does a fresh install
ruff check .
mypy --strict .
pytest

# 2. Run the Java suite
cd ../orchestrator && mvn verify

# 3. Run the demo-app suite
cd ../demo-app && mvn verify

# 4. Confirm by pushing the fix branch and watching CI
# All four CI jobs (orchestrator, agents, demo-app, ui if it exists)
# must go green.
```

**Don't accept the fix until CI itself confirms green** — local runs
have already been misleading us for weeks.

---

## Step 6 — Once CI is green, audit the damage

If `main` was red the whole time (Case B from Step 1):

```bash
# What commits landed during the red period?
git log <last-green-sha>..main --oneline

# Were any of them "fix" commits that didn't actually fix anything?
# Were Sprint 3 and Sprint 4 features built on top of unverified code?
```

For each PR that merged into red `main`, the questions:

- Did it touch the same area as the original failure? If yes, it may
  have been compounding the bug.
- Does its own test suite (the new tests it added) actually pass *now*
  on green main? If not, those features may be broken too.

For Sprint 4 specifically (Days 27-29):

- pgvector migration applied cleanly? (`SELECT extname FROM
  pg_extension WHERE extname = 'vector'`)
- History agent's `TC-4.2.x` tests pass against green main?
- Topology agent's `TC-4.3.x` tests pass against green main?

If any of these are actually broken and were merging into red CI, fix
them now, before adding the Runbook agent on Day 30.

---

## Step 7 — Honest documentation in PROGRESS.md

Add a section called "CI breakage discovered Day 29":

```markdown
### CI breakage — discovered during Day 29

On 2026-06-21, while preparing the Day-30 plan, a screenshot of CI run
#43 (PR #19, Sprint 2 close) showed the agents (Python) job failing
with exit code 1. Investigation found <Case A/B/C from Step 1>.

Root cause: <what the actual log showed>.

Affected period: <date range during which main was red, if applicable>.

Fix: <what changed>.

Lesson: <e.g. "post-merge CI status was not being verified after each
sprint close; we were trusting the PR's pre-merge green without
re-checking after the merge collapsed onto main">.
```

This is the kind of incident worth writing down honestly. It's also
exactly the kind of thing an interviewer asks about — *"tell me about
a time you discovered a bug that had been quietly broken for weeks."*
The honest answer to that question is more valuable than the bug not
existing.

---

## What NOT to do

- **Do not merge the Day-30 PR before main is green.** It will compound
  the problem.
- **Do not "skip" the failing test temporarily** to get a green CI.
  That's the move that creates the kind of red-but-everyone-ignores-it
  CI culture this project has carefully avoided.
- **Do not blindly re-run CI hoping the failure was flaky.** If the
  same PR fails twice in a row, it's not flaky; it's broken.
- **Do not roll back the sprint-2 / sprint-3 tags** until you know
  the project state. The tags themselves aren't the problem; the
  question is whether the *commits* they point at are sound.

---

## Status — fill this in as you go

- [x] Step 1 done — Case A: main is and has been green (2026-06-22)
- [x] Step 2 done — no live failure; run #43 was stale (fix commits landed before PR #19 merged)
- [x] Step 3 done — not applicable (CI was already green; no hypothesis needed)
- [x] Step 4 done — PR #28 wrong-base corrected: PR #29 merged topology agent → main
- [x] Step 5 done — CI green on main after PR #29 merge (all 4 jobs: agents, orchestrator, demo-app, ui)
- [x] Step 6 done — no compounded breakage; history + topology agents verified green in CI
- [x] Step 7 done — PROGRESS.md documents CI audit finding and lesson learned
- [x] Sprint 4 Day 30 plan can now be requested

---

## How to use this file with Claude Code

Hand this entire file to Claude Code. Opening instruction:

> "Read `docs/incidents/CI-MAIN-RED-FIX.md`. Sprint 4 Day 30 is blocked
> until CI on main is green. Start at Step 1: run the three commands
> and report back what you find before doing anything else. Do NOT
> skip to Step 4's fixes — we need to know what actually broke before
> we know how to fix it. Do NOT touch any code outside the failing
> test or its direct dependencies until the root cause is identified."

Claude Code should:
1. Run the Step 1 commands and paste the output.
2. Pull the failing CI log (Step 2) and paste the pytest output.
3. Identify which hypothesis matches and propose the fix.
4. **Pause for human confirmation** before applying the fix.
5. After fix is applied and CI is green, do the Step 6 audit.
6. Update PROGRESS.md per Step 7.

Then — and only then — ask for the Day 30 plan.
