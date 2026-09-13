package software.sava.http_servers.netty;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.handler.codec.http.HttpServerCodec;
import software.sava.http_servers.core.handlers.HandlerMap;

import java.util.concurrent.Executor;

/// Builds each accepted connection's pipeline, in order: the HTTP/1.x codec; the
/// [NettyRequestGate], which lets one request at a time through and owns the connection's
/// ordering and persistence; the [NettyRequestAggregator], which collects that request's
/// body into one `FullHttpRequest` (answering `Expect: 100-continue`, an unsupported `Expect`
/// with 417 and an over-long body with 413 — all behind the gate, so in request order); and a
/// [NettyController], which routes it. All three are per-connection instances. Typed on
/// `Channel` rather than `SocketChannel` so the same wiring can be exercised in process on an
/// `EmbeddedChannel` (`NettyPipelineTest`); nothing here needs the socket-specific config.
final class NettyChannelInitializer extends ChannelInitializer<Channel> {

  private final HandlerMap<NettyHandler> handlerMap;
  private final Executor executor;
  private final int maxContentLength;

  NettyChannelInitializer(final HandlerMap<NettyHandler> handlerMap,
                          final Executor executor,
                          final int maxContentLength) {
    this.handlerMap = handlerMap;
    this.executor = executor;
    this.maxContentLength = maxContentLength;
  }

  @Override
  protected void initChannel(final Channel channel) {
    channel.pipeline().addLast(
        new HttpServerCodec(),
        new NettyRequestGate(),
        new NettyRequestAggregator(maxContentLength),
        new NettyController(handlerMap, executor)
    );
  }
}
