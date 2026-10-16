# The Network can't reach the platform

**Signals:** the demo answers `500` after its Payment completes; the simulator logs `Servlet.service() … threw exception` with `io.grpc.StatusRuntimeException: UNAVAILABLE`; the platform logs no `Authorization … answered` line for the Charge, and `"authorization".authorizations` has no row for it.

The simulator's Network sends each Charge to the platform's gRPC server at `simulator.platform-grpc-address` (`localhost:9090` by default), where the platform listens on `127.0.0.1:9090` (`spring.grpc.server.address` and `.port`); see [The simulator](../architecture.md#the-simulator). When gRPC can't connect, it fails the call at once with `UNAVAILABLE`. Until #37 gives the Network its 1-second deadline, nothing declines the Charge or sends a Reversal, and a platform that accepts the connection but never answers leaves the demo waiting.

1. **Is the platform running?** Its log says `gRPC Server started, listening on address: [/127.0.0.1:9090]`. If it isn't running, start it: `./mvnw -pl platform -am spring-boot:run`.
2. **Is port 9090 someone else's?** `ss -ltnp | grep 9090` (or `lsof -i :9090`) shows what holds it. Stop that process, or move the platform's `spring.grpc.server.port` and the simulator's `simulator.platform-grpc-address` together.
3. **Do the two settings agree?** The simulator's `simulator.platform-grpc-address` must name the platform's `spring.grpc.server.address` and `.port`. `localhost` reaches a server on `127.0.0.1`, as the tests show.

Nothing is lost: the card stays `ACTIVE` with its Hold, and if no Charge reaches it within 30 days it expires and its Hold is released. A new demo Invoice charges its own new card.
