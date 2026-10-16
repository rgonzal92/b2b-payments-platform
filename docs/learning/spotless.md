# Spotless

## The problem it solves here

Agents write the code and you review it. If formatting varies, every pull request mixes whitespace changes with logic changes, and review attention goes to the wrong lines. Spotless makes one formatter, google-java-format, the only accepted layout, so a diff shows logic and nothing else.

## Mental model

- **`check` is a build gate.** It compares every Java file with what google-java-format would produce and fails the build on any difference. It runs in the `verify` phase, so `./mvnw verify` and CI enforce it, while `./mvnw test` doesn't.
- **`apply` is the fix.** `./mvnw spotless:apply` rewrites files into the canonical format. Nothing is argued about; the tool decides.
- **Configured once, in the parent `pom.xml`,** so both modules, and any future module, inherit the same rules.

## Gotchas we hit

- **google-java-format is pinned to 1.36.1.** Version 1.37.0 fails under Spotless 3.10.4 with an `InvocationTargetException`; the pin and its reason come from `vehicle-catalog`.
- **Initializr generates tab-indented Java.** google-java-format uses two spaces, so the first `apply` rewrote every generated file.
- **JDK 25 prints `sun.misc.Unsafe` deprecation warnings** from Spotless's own library on every run. They are noise, not failures.
- **The gate was proven, not assumed:** a mis-indented `SimulatorApplication` made `verify` fail with "The following files had format violations", and `apply` fixed it.
- **Only Java is checked.** POMs and Markdown files aren't formatted.
