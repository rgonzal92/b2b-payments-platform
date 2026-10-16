# OpenAPI and openapi-generator

## The problem it solves here

The platform serves an API and the simulator calls it. If each side hand-writes its own view of a request, they drift apart and the mismatch shows up at runtime. Writing the API once as an OpenAPI document, reviewed before any code, and generating both sides from it, turns a mismatch into a compile error.

## Mental model

- **`contracts/src/main/resources/openapi/platform-api.yaml` is the source of truth:** paths, request and response bodies, validation rules (`minLength`, `minimum`, `enum`), and which errors are problem details.
- **The `contracts` module generates two things at build time:**
  - server interfaces (`contracts.server`), which the platform's controllers implement, with Bean Validation annotations taken from the document
  - HTTP interfaces (`contracts.client`), which the simulator turns into working clients with Spring's `HttpServiceProxyFactory`
- **Generated code is never edited.** Change the document, rebuild, and fix whatever no longer compiles.

## Gotchas we hit

- **Spring Boot 4 needs two flags.** Boot 4 uses Jackson 3, and the default Java client imports Jackson 2 classes that aren't on the classpath. `useSpringBoot4=true` and `useJackson3=true` fix both sides; the generated models then use only Jackson's annotations, which Jackson 3 still shares.
- **HTTP interfaces beat the default Java client.** The `spring-http-interface` library generates plain `@HttpExchange` interfaces with no invoker classes; the simulator supplies the `RestClient`, including the OAuth2 interceptor that adds its token.
- **Boot 4 split `RestClient.Builder` into its own starter** (`spring-boot-starter-restclient`). Without it there is no builder bean, and with it Boot's builder propagates trace headers between the apps.
- **Running one app needs `-am`.** The apps depend on `contracts`, so `./mvnw -pl platform -am spring-boot:run` builds it first; `spring-boot.run.skip` keeps `spring-boot:run` from trying to start the parent and `contracts`, which have no main class.
- **Generated models share names with domain types.** `Buyer` exists in both `contracts.server.model` and the `buyers` module, so the controller refers to the generated one by its full name.
