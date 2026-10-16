# `build` is red at the SonarQube step

**Signal:** the `build` check fails in its "Analyze with SonarQube Cloud" step while "Verify" passed. Once the `main` ruleset is on, nothing can merge until it's green.

Read the step's log first; the last lines name the cause.

## The quality gate failed

The log ends with `QUALITY GATE STATUS: FAILED` and a link to the analysis.

1. Open the link. The gate lists which condition failed on new code: coverage, bugs, vulnerabilities, security hotspots or duplication.
2. Fix it in the pull request: add the missing tests, fix the issue, or remove the duplication. Push, and the check re-runs.
3. A finding that is genuinely wrong can be marked "False positive" or "Accept" in SonarQube Cloud, with a comment saying why. Then re-run the job.

## The token was rejected

The log shows `401`, `Not authorized` or a message about the token.

1. In SonarQube Cloud, under My Account → Security, generate a new token.
2. Replace the repository secret: `gh secret set SONAR_TOKEN` and paste the token at the prompt. Never paste it into a file, a command line or a chat.
3. Re-run the failed job.

## SonarQube Cloud is unavailable

The log shows timeouts, `5xx` responses, or the gate wait expiring after 300 seconds.

1. Check <https://sonarcloud.statuspage.io>.
2. Re-run the job once the service is back. Nothing in the repository needs changing.
3. If an outage blocks an urgent merge, edit the `main` ruleset and remove the `build` requirement for that merge, then restore it straight away. Record why in the pull request.
