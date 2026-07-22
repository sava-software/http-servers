package software.sava.http_servers.jetty;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Handler;

public abstract class BaseJettyHandler extends Handler.Abstract implements JettyHandler {

  public static final HttpField JSON_CONTENT = new HttpField(HttpHeader.CONTENT_TYPE, "application/json");

  protected BaseJettyHandler(final InvocationType invocationType) {
    super(invocationType);
  }
}
