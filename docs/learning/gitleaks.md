# gitleaks

## The problem it solves here

The repository is public, and agents commit on your behalf. A token or password committed once stays readable in the git history forever, even after a later commit deletes it. gitleaks scans every commit for things that look like secrets (API keys, private keys, high-entropy strings) and fails the `secrets` check when it finds one.

## Mental model

- **It scans history, not just files.** `gitleaks git .` walks every commit reachable in the clone, so CI checks out the full history (`fetch-depth: 0`).
- **A finding fails the job.** gitleaks exits non-zero, the `secrets` check goes red, and the ruleset on `main` blocks the merge.
- **Two layers, different moments.** GitHub's push protection rejects a recognised token at `git push`; gitleaks in CI catches what push protection doesn't recognise, across the whole history.
- **A real leak means rotating the secret.** Deleting it in a new commit leaves it in the history.

## Gotchas we hit

- **The official action scans less than it looks.** `gitleaks/gitleaks-action` scans only the commits in the push or pull request that triggered it; the full history is scanned only on scheduled or manual runs. CI runs the gitleaks CLI directly instead.
- **The CLI download is pinned by version and SHA-256 checksum**, so a tampered release file fails the job instead of running. Dependabot doesn't update this pin: bump `GITLEAKS_VERSION` and `GITLEAKS_SHA256` together, by hand, from the release's checksums file.
- **`--redact` keeps findings out of the logs.** CI logs on a public repository are public; without it, gitleaks would print the secret it just found.
