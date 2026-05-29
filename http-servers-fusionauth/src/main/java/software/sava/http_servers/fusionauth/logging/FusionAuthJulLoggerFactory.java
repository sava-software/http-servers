package software.sava.http_servers.fusionauth.logging;

import io.fusionauth.http.log.Logger;
import io.fusionauth.http.log.LoggerFactory;

/**
 * FusionAuth LoggerFactory that delegates to java.util.logging so the output is
 * controlled by the application's JUL configuration and formatters.
 */
public final class FusionAuthJulLoggerFactory implements LoggerFactory {

  @Override
  public Logger getLogger(final Class<?> klass) {
    return new FusionAuthJulLogger(klass);
  }
}
