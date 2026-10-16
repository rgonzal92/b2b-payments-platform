# GitHub rulesets

## The problem it solves here

Every check in CI is advisory until something refuses to merge when it fails. A ruleset on `main` is that something: changes arrive only through pull requests, and only when `build` and `secrets` are green. It holds for everyone, the owner and agents alike.

## Mental model

- **A ruleset is a named set of rules applied to branches.** This repository has one, `main`, targeting the default branch.
- **Its rules:**
  - changes need a pull request (zero approvals required)
  - the `build` and `secrets` checks must pass
  - force pushes and deleting `main` are blocked
- **Required checks are tied to the GitHub Actions app,** so only a real workflow run can satisfy them, not a status posted by some other tool under the same name.
- **It lives in the repository's settings, not in a file.** Settings → Rules → Rulesets shows it, and `gh api repos/rgonzal92/b2b-payments-platform/rulesets` lists it.

## Gotchas we hit

- **Zero required approvals is deliberate.** GitHub won't let you approve your own pull request, and agents open pull requests under your account, so requiring one approval would block every merge. Review stays your discipline (`CLAUDE.md`, "Pull requests").
- **Branches don't need to be up to date before merging.** Two pull requests can each pass against an older `main`; the solo pace makes that rare, and requiring updates would force a re-run for every merge.
- **Renaming a CI job strands its pull request.** The ruleset requires checks by job id, so the pull request that renames `build` or `secrets` waits for a check that never reports and can't merge until the ruleset is updated to the new name. `main` itself is unaffected.
