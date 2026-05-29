package systems.glam.server.core.rest.ws;

import software.sava.rpc.json.http.ws.SolanaRpcWebsocket;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

public interface WebsocketListener extends
    Consumer<SolanaRpcWebsocket>,
    SolanaRpcWebsocket.OnClose,
    BiConsumer<SolanaRpcWebsocket, Throwable> {

  default void onOpen(final SolanaRpcWebsocket ws) {

  }

  @Override
  default void accept(final SolanaRpcWebsocket ws) {
    onOpen(ws);
  }

  default void onError(final SolanaRpcWebsocket ws, final Throwable error) {

  }

  @Override
  default void accept(final SolanaRpcWebsocket ws, final Throwable error) {
    onError(ws, error);
  }

  default void onClose(final SolanaRpcWebsocket ws, final int statusCode, final String reason) {

  }

  @Override
  default void accept(final SolanaRpcWebsocket ws, final int statusCode, final String reason) {
    onClose(ws, statusCode, reason);
  }
}
