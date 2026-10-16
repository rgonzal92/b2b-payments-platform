# A secret was committed

**Signal:** the `secrets` check is red, GitHub push protection rejected a push, or a GitHub secret-scanning alert arrived.

The repository is public: treat any secret that reached GitHub as already copied. Rotating it is the fix; everything else is cleanup.

## If the check is red on a pull request

1. Open the `secrets` job log. Findings are redacted: note the file, line, commit and rule, never the value.
2. **Real secret:** rotate it first, at its issuer (revoke the token, change the password). Do this before anything else, even if the pull request is never merged: its commits are already public on GitHub.
3. Remove it from the branch. While the branch is unmerged, rewrite it (`git rebase -i` to drop or edit the commit) and force push the branch. `main`'s force-push block doesn't apply to feature branches.
4. **False positive** (a test fixture, a high-entropy ID): add the finding's fingerprint from the log to a `.gitleaksignore` file at the repo root, with a comment saying why, in the same pull request.

## If the secret is already on `main`

1. Rotate it at its issuer, immediately.
2. Leave the history alone unless the secret can't be rotated. Rewriting `main` needs the ruleset's force-push block lifted, breaks every clone and fork, and doesn't remove copies GitHub or others already made.
3. Close the GitHub secret-scanning alert as "revoked" once rotation is done.

## Prevention

Secrets come from environment variables or a local `.env` file, which `.gitignore` excludes; nothing in the repository holds a real credential.
