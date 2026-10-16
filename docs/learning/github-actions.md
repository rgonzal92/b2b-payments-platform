# GitHub Actions

## The problem it solves here

Agents open the pull requests, and you review them. CI gives every pull request the same verdict on the same machine before you read a line: does it build, do the tests pass, did a secret slip in. Its two checks, `build` and `secrets`, become the gate the `main` ruleset enforces (#6).

## Mental model

- **A workflow is a YAML file** in `.github/workflows/`, run on events: here every pull request and every push to `main`.
- **Jobs run in parallel on fresh virtual machines.** `build` and `secrets` share nothing; each checks out the code itself.
- **A job's id is its check name.** The ruleset requires checks by name, so the pull request that renames `build` or `secrets` waits for a check that never reports, until the ruleset is updated (see `github-rulesets.md`).
- **Steps either run a command or use an action,** a published, reusable step such as `actions/checkout`.

## Gotchas we hit

- **Actions are pinned to a commit SHA, not a tag.** A tag like `v7` can be moved to different code by whoever controls the action's repository; a SHA can't. The `# v7.0.1` comment keeps it readable, and the SHAs match `vehicle-catalog`.
- **A concurrency group cancels superseded runs.** A new push to a pull request cancels that pull request's older run; runs on `main` get a group of their own, so they're never cancelled.
- **`permissions: contents: read`** limits the workflow's token to reading the repository, so a compromised step can't push or comment.
- **Testcontainers' clean-up container (Ryuk) is turned off in CI**: the runner is thrown away after each run, so there is nothing to clean, and one fewer image to pull.
