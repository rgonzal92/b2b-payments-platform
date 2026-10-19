package dev.rgonz.payments.gateway;

import io.lettuce.core.ClientOptions.DisconnectedBehavior;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.support.ConfigurationService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;

/**
 * Whose bucket a request takes a token from (M6 design, Rate limiting), and what happens when Redis
 * fails: the request passes and is counted (ADR-0017). The limits themselves are the routes'
 * RequestRateLimiter arguments in application.properties.
 */
@Configuration(proxyBeanMethods = false)
class RateLimits {

  /**
   * The Keycloak client the token was issued to (its {@code azp}), so every user of one client
   * shares its limit. The default: every route without its own resolver uses it. A token without
   * {@code azp} takes its subject's bucket, and one without either a single shared bucket: an empty
   * key would get a bare 403 from RequestRateLimiter.
   */
  @Bean
  @Primary
  KeyResolver keycloakClient() {
    return exchange ->
        exchange
            .getPrincipal()
            .filter(JwtAuthenticationToken.class::isInstance)
            .map(principal -> ((JwtAuthenticationToken) principal).getToken())
            .map(token -> clientOf(token.getClaimAsString("azp"), token.getSubject()));
  }

  private static String clientOf(String azp, String subject) {
    if (azp != null) {
      return azp;
    }
    return subject != null ? "sub:" + subject : "unidentified";
  }

  /**
   * The address the connection came from, for the remittance link, which has no token. Forwarding
   * headers a client sends are not trusted: the Gateway strips them, and anyone could send a new
   * one with every request.
   */
  @Bean
  KeyResolver clientIp() {
    return exchange ->
        Mono.justOrEmpty(exchange.getRequest().getRemoteAddress())
            .map(address -> address.getAddress().getHostAddress());
  }

  /** Replaces Spring Cloud Gateway's own RedisRateLimiter bean, which backs off for this one. */
  @Bean
  RedisRateLimiter redisRateLimiter(
      ReactiveStringRedisTemplate redis,
      @Qualifier(RedisRateLimiter.REDIS_SCRIPT_NAME) RedisScript<List<Long>> script,
      ConfigurationService configuration,
      MeterRegistry meters) {
    return new FailOpenCountingRateLimiter(redis, script, configuration, meters);
  }

  /**
   * RedisRateLimiter, counting what it lets through unlimited. It already fails open: on any Redis
   * error it logs "Error calling rate limiter lua" under this class's name, allows the request and
   * reports -1 tokens remaining, a count its token bucket never returns. Grafana alerts on the
   * counter (alerting.yaml).
   */
  static class FailOpenCountingRateLimiter extends RedisRateLimiter {

    private final Counter failedOpen;

    FailOpenCountingRateLimiter(
        ReactiveStringRedisTemplate redis,
        RedisScript<List<Long>> script,
        ConfigurationService configuration,
        MeterRegistry meters) {
      super(redis, script, configuration);
      // Registered at startup, so the first failure is an increase Prometheus can see.
      this.failedOpen =
          Counter.builder("gateway.rate.limiter.failed.open")
              .description("Requests let through without a rate limit because Redis failed")
              .register(meters);
    }

    @Override
    public Mono<Response> isAllowed(String routeId, String id) {
      return super.isAllowed(routeId, id)
          .doOnNext(
              response -> {
                if ("-1".equals(response.getHeaders().get(REMAINING_HEADER))) {
                  failedOpen.increment();
                }
              });
    }
  }

  /**
   * While Lettuce reconnects to a Redis that went away, it fails each command at once instead of
   * holding it until the command timeout, so the limiter lets requests through without waiting.
   */
  @Bean
  LettuceClientOptionsBuilderCustomizer failFastWhileRedisIsDown() {
    return options -> options.disconnectedBehavior(DisconnectedBehavior.REJECT_COMMANDS);
  }
}
