package software.sava.http_servers.fusionauth.logging;

import io.fusionauth.http.log.Logger;
import software.sava.http_servers.core.logging.BaseJulLogger;

import java.util.logging.Level;

final class FusionAuthJulLogger extends BaseJulLogger implements Logger {

  FusionAuthJulLogger(final Class<?> klass) {
    super(klass);
  }

  @Override
  public boolean isDebugEnabled() {
    return jul.isLoggable(Level.FINE);
  }

  @Override
  public boolean isErrorEnabled() {
    return jul.isLoggable(Level.SEVERE);
  }

  @Override
  public boolean isInfoEnabled() {
    return jul.isLoggable(Level.INFO);
  }

  @Override
  public boolean isTraceEnabled() {
    return jul.isLoggable(Level.FINER);
  }

  @Override
  public void setLevel(final io.fusionauth.http.log.Level level) {
    if (level != null) {
      switch (level) {
        case Trace -> jul.setLevel(Level.FINER);
        case Debug -> jul.setLevel(Level.FINE);
        case Info -> jul.setLevel(Level.INFO);
        case Error -> jul.setLevel(Level.SEVERE);
      }
    }
  }

  @Override
  public void debug(final String message) {
    log(Level.FINE, message, null);
  }

  @Override
  public void debug(final String message, final Object... values) {
    logFormat(Level.FINE, message, values);
  }

  @Override
  public void debug(final String message, final Throwable throwable) {
    log(Level.FINE, message, throwable);
  }

  @Override
  public void error(final String message) {
    log(Level.SEVERE, message, null);
  }

  @Override
  public void error(final String message, final Throwable throwable) {
    log(Level.SEVERE, message, throwable);
  }

  @Override
  public void info(final String message) {
    log(Level.INFO, message, null);
  }

  @Override
  public void info(final String message, final Object... values) {
    logFormat(Level.INFO, message, values);
  }

  @Override
  public void trace(final String message) {
    log(Level.FINER, message, null);
  }

  @Override
  public void trace(final String message, final Object... values) {
    logFormat(Level.FINER, message, values);
  }
}
