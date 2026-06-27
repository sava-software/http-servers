package software.sava.http_servers.core.handlers;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Stream;

public interface HandlerMap<H> {

  static <H> HandlerMap<H> createController(final Map<String, Map<String, H>> queryHandlers,
                                            final Collection<Map.Entry<String, Map<String, H>>> pathHandlers) {
    return new HandlerMapImpl<>(queryHandlers, pathHandlers);
  }

  HandlerLookup<H> lookupHandler(final String method, final String path);

  Stream<Map.Entry<String, Map<String, H>>> queryHandlerStream();

  Stream<Map.Entry<String, Map<String, H>>> pathHandlerStream();
}
