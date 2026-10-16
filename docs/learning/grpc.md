# gRPC and protobuf

## The problem it solves here

Every Charge on a card makes the Network ask the platform, in real time, whether to approve it; 99% of answers must leave within 100 ms, and the Network gives up after 1 second (ADR-0010). Real Networks send these requests as ISO 8583 binary messages over long-lived TCP connections. gRPC gives the same shape, a typed request and answer over a connection that stays open, without writing a binary codec first (ADR-0006). The contract is a `.proto` file both sides generate code from, so the platform and the simulator's Network can't drift apart, and its field names are ISO 8583's, so a later move to real ISO 8583 is a mapping.

## Mental model

- **Contract first.** `contracts/src/main/proto/issuer_authorization.proto` defines the `IssuerAuthorization` service (`Authorize`, `Reverse`) and its messages. At build time `protoc` generates a Java class per message (with a builder) and `IssuerAuthorizationGrpc`, which holds the client stubs and the `IssuerAuthorizationImplBase` the platform extends. Generated code is never edited: change the `.proto` and rebuild.
- **Field numbers are the wire format, names are for people.** Protobuf encodes each field as its number and a compact binary value, never its name. Renaming a field is safe; changing or reusing a number breaks every client already deployed. Adding a field with a new number is backward compatible: old readers skip it.
- **proto3 has no null.** An unset string reads as `""` and an unset `int64` as `0`. A decline's authorization code is therefore `""` on the wire, and the platform stores it as `NULL`.
- **One HTTP/2 connection, many calls.** A gRPC call is an HTTP/2 request: `POST /payments.network.v1.IssuerAuthorization/Authorize` with the protobuf bytes as its body. HTTP/2 multiplexes many calls over one connection, so after the first call there's no TCP or TLS handshake on the latency budget.
- **Deadlines travel with the call.** A client sets a deadline (`withDeadlineAfter`), sent as the `grpc-timeout` header; when it passes, the client gets `DEADLINE_EXCEEDED` and the server's call is cancelled. The Network will use 1 second (#36), then decline the Charge and send a Reversal (#33). The tests use 5 seconds, so a hang fails fast instead of stalling the build.
- **A gRPC status says whether the call worked; an ISO 8583 response code says what the Issuer answered.** A decline is a successful call: status `OK`, `response_code` `"14"` (invalid card), `"54"` and the rest ([`authorization-message.md`](authorization-message.md)). gRPC statuses are for calls that never got an answer: `UNAUTHENTICATED` (no or bad token), `PERMISSION_DENIED` (not the Network), `INVALID_ARGUMENT` (no retrieval reference number to answer under or to reverse), `UNIMPLEMENTED` (a method the service doesn't override), `DEADLINE_EXCEEDED`, `UNKNOWN` (a bug). Mixing the two would make the Network treat a bug as a decline, or a decline as an outage.
- **Metadata carries the token.** Metadata is gRPC's name for HTTP/2 headers. The Network sends `authorization: Bearer <token>`, a Keycloak token for the `network` client. Spring gRPC's `GrpcSecurity` builds an `AuthenticationProcessInterceptor`, a server interceptor that runs before the service: it validates the token with the same JWT decoder, issuer and `roles` claim as REST and requires `ROLE_network` for every call (`SecurityConfig`).

## How it's built here

```text
Network ──HTTP/2, authorization: Bearer <network token>──▶ Netty on 127.0.0.1:9090
  └─ AuthenticationProcessInterceptor     no or bad token: UNAUTHENTICATED; no ROLE_network: PERMISSION_DENIED
       └─ IssuerAuthorizationService       @GrpcService, extends IssuerAuthorizationImplBase
            ├─ Authorizations.authorize    one transaction: the Authorization rules, then the answer
            │    ├─ vault.cardFor(PAN)      Card Token through the PAN's keyed hash; none: 14
            │    ├─ vault.securityCodeMatches, cards.find, AuthorizationRules.decide
            │    ├─ insert into "authorization".authorizations … on conflict do nothing
            │    └─ approved: cards.authorize(token)   ACTIVE → AUTHORIZED in one conditional update
            └─ Authorizations.reverse      one transaction, acknowledged 00: claim the request ID as
                                           reversed, or reverse its approval; cards.reverse(token)
```

- **Spring Boot's own gRPC support.** `spring-boot-starter-grpc-server` runs a Netty gRPC server beside Tomcat and exposes every `BindableService` bean (`@GrpcService`). Boot's parent configures `io.github.ascopes:protobuf-maven-plugin` with matching `protoc` and `protoc-gen-grpc-java` versions, so `contracts` only declares the plugin. Older guides use other libraries (`net.devh`, LogNet); this project doesn't.
- **Plaintext, on this machine only.** The server listens on `127.0.0.1` (`spring.grpc.server.address`; the default is every interface), and the connection isn't encrypted: mutual TLS arrives in M7.
- **Tests talk to a real server.** Each test context's server takes a free port (`spring.grpc.server.port=0`), read with `@LocalGrpcServerPort`; the test opens a plaintext Netty channel and sends the token with Spring gRPC's `BearerTokenAuthenticationInterceptor`. `NetworkAuthenticationTests` uses real Keycloak tokens, `AuthorizeTests` a mocked one.

## Gotchas we hit

- **Boot already secures gRPC, but only half way.** With a `JwtDecoder` in the context, Boot auto-configures an interceptor that requires *any* valid token: the "no token" test passed before we wrote any security code, while `acme-erp`'s token got answers. Our own `AuthenticationProcessInterceptor` bean replaces Boot's (it backs off when one exists) and requires `ROLE_network`.
- **A forged token fails before any rule.** Authentication runs first, so even `permitAll()` answers `UNAUTHENTICATED` to a token the realm didn't sign; only a missing token reaches the rules as anonymous.
- **`roles` → `ROLE_network` comes for free.** `GrpcSecurity`'s `jwt()` reuses the `JwtAuthenticationConverter` bean Boot builds from `authorities-claim-name=roles`, so gRPC and REST read roles the same way.
- **The starter adds reflection and health services** (`grpc-services`). The rule covers them too: every call needs the Network's token.
- **Fixed ports and tests don't mix.** Every cached Spring test context starts its own gRPC server, and other builds may run on the same machine; on 9090 they would collide. The test configuration sets port `0`; the end-to-end test, which starts the platform with only its main properties, picks a free port for it.
- **A protobuf message's `toString` prints every field**, PAN and security code included. Nothing logs a request; a throwaway change logging one made `CorePathTests` fail on the PAN in the captured logs.
- **`authorization` is a reserved word in PostgreSQL.** `select … from authorization.authorizations` is a syntax error; SQL must say `"authorization".authorizations`. Flyway quotes schema names itself, and the PAN search builds names with `format('%I.%I')`, which quotes them too. The table is plural so it needn't be quoted as well.
- **A method you don't override answers `UNIMPLEMENTED`.** The generated `ImplBase` did that for `Reverse` until #33 overrode it.
- **An exception in a service becomes `UNKNOWN`, without its message.** The client never sees the exception's text, and Spring gRPC logs it only at `DEBUG`. Good for keeping PANs out of answers, harder for debugging.
- **Traces record the call, not its content.** Micrometer's gRPC observation names the span after the full method name and records the status and the caller's address and port; never message fields or metadata.
