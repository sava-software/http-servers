package software.sava.http_servers.core.handlers;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Stream;

record HandlerMapImpl<H>(Map<String, Map<String, H>> queryHandlers,
                         Collection<Map.Entry<String, Map<String, H>>> pathHandlers) implements HandlerMap<H> {

  @Override
  public HandlerLookup<H> lookupHandler(final String method, final String path) {
    var methodHandlers = queryHandlers.get(path);
    if (methodHandlers == null) {
      for (final var entry : pathHandlers) {
        if (path.startsWith(entry.getKey())) {
          methodHandlers = entry.getValue();
          break;
        }
      }
      if (methodHandlers == null) {
        return HandlerLookup.notFound();
      }
    }
    final var handler = methodHandlers.get(method);
    if (handler != null) {
      return HandlerLookup.matched(handler);
    }
    return HandlerLookup.methodNotAllowed(String.join(", ", methodHandlers.keySet()));
  }

  @Override
  public Stream<Map.Entry<String, Map<String, H>>> queryHandlerStream() {
    return queryHandlers.entrySet().stream();
  }

  @Override
  public Stream<Map.Entry<String, Map<String, H>>> pathHandlerStream() {
    return pathHandlers.stream();
  }
}
