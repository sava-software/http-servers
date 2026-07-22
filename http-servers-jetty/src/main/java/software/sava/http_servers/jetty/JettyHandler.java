package software.sava.http_servers.jetty;

import org.eclipse.jetty.server.Handler;

/// Names the handler type this module's builder produces and [JettyController] routes to.
/// CORS pre-flights are answered by the controller, not by handlers.
public interface JettyHandler extends Handler {
}
