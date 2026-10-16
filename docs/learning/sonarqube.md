# SonarQube Cloud

## The problem it solves here

Agents write the code and you review it, but some problems are easy to miss in a diff: a resource never closed, a branch no test reaches, duplicated logic, a known-vulnerable pattern. SonarQube Cloud analyses every pull request for bugs, vulnerabilities, code smells and test coverage, and its quality gate turns "looks fine" into a pass or fail on the `build` check.

## Mental model

- **CI runs the analysis; SonarQube Cloud stores and judges it.** After `verify`, the `build` job runs the Sonar Maven plugin, which reads the compiled classes, the source and the JaCoCo XML reports, and uploads the results.
- **The quality gate judges new code.** The default gate ("Sonar way") checks what a pull request adds or changes: enough coverage, no new bugs or vulnerabilities, little duplication. Old code is never retro-failed.
- **`-Dsonar.qualitygate.wait=true` makes the gate a CI result.** The plugin waits for SonarQube Cloud's verdict and fails the step when the gate fails, so the `build` check goes red.
- **The token stays in GitHub.** `SONAR_TOKEN` is a repository secret, passed only to the analysis step; the organization and project keys aren't secret and sit in the parent `pom.xml`.

## Gotchas we hit

- **Automatic analysis had to be turned off.** SonarQube Cloud can analyse a repository on its own, but that conflicts with analysis from CI; only one may run.
- **The analysis needs the full history.** CI checks out every commit (`fetch-depth: 0`) so SonarQube can use blame data to tell new code from old.
- **Pull requests from forks and from Dependabot skip the analysis.** Neither receives the repository's secrets, so the step would fail without a token and turn `build` red. Dependabot's changes are analysed after merging, on the push to `main`.
