package dev.rgonz.payments.platform;

import dev.rgonz.payments.contracts.server.RemittanceLinksApi;
import io.micrometer.common.KeyValue;
import org.springframework.http.server.observation.DefaultServerRequestObservationConvention;
import org.springframework.http.server.observation.ServerHttpObservationDocumentation.HighCardinalityKeyNames;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.stereotype.Component;

/**
 * What a traced HTTP request records. A Remittance Advice link's path holds its token, which is a
 * credential, so a request to one records the route instead of its URL.
 */
@Component
class RequestObservations extends DefaultServerRequestObservationConvention {

  private static final String LINK_ROUTE = RemittanceLinksApi.PATH_OPEN_REMITTANCE_LINK;
  private static final String LINK_PREFIX = LINK_ROUTE.substring(0, LINK_ROUTE.indexOf('{'));

  @Override
  protected KeyValue httpUrl(ServerRequestObservationContext context) {
    if (context.getCarrier().getRequestURI().startsWith(LINK_PREFIX)) {
      return KeyValue.of(HighCardinalityKeyNames.HTTP_URL, LINK_ROUTE);
    }
    return super.httpUrl(context);
  }
}
