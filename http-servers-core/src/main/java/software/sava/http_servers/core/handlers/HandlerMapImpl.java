package software.sava.http_servers.core.handlers;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Stream;

record HandlerMapImpl<H>(Map<String, H> queryHandlers,
                         Collection<Map.Entry<String, H>> pathHandlers) implements HandlerMap<H> {

  @Override
  public H lookupHandler(final String path) {
    final var handler = queryHandlers.get(path);
    if (handler == null) {
      for (final var entry : pathHandlers) {
        if (path.startsWith(entry.getKey())) {
          return entry.getValue();
        }
      }
      return null;
    } else {
      return handler;
    }
  }

  @Override
  public Stream<Map.Entry<String, H>> queryHandlerStream() {
    return queryHandlers.entrySet().stream();
  }

  @Override
  public Stream<Map.Entry<String, H>> pathHandlerStream() {
    return pathHandlers.stream();
  }
}
