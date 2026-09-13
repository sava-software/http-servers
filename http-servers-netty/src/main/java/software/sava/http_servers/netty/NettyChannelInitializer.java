package software.sava.http_servers.netty;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.handler.codec.http.HttpServerCodec;
import software.sava.http_servers.core.handlers.HandlerMap;

import java.util.concurrent.Executor;
import java.util.function.LongSupplier;

/// Builds each accepted connection's pipeline, in order: the HTTP/1.x codec; the
/// [NettyRequestGate], which lets one request at a time through and owns the connection's
/// ordering, persistence and idle timeout (measured on `clock`, `System::nanoTime` in
/// production), and refuses an unsupported `Expect` (417) or an `Expect: 100-continue`
/// announcing a body over `maxContentLength` (413) as each head is decoded, answering in
/// request order; the [NettyRequestAggregator], which collects that request's body into one
/// `FullHttpRequest` (answering an acceptable `Expect: 100-continue` with `100` and an
/// over-long body with 413 — behind the gate, so in request order); and a
/// [NettyController], which routes it. All three are per-connection instances. Typed on
/// `Channel` rather than `SocketChannel` so the same wiring can be exercised in process on an
/// `EmbeddedChannel` (`NettyPipelineTest`); nothing here needs the socket-specific config.
final class NettyChannelInitializer extends ChannelInitializer<Channel> {

  private final HandlerMap<NettyHandler> handlerMap;
  private final Executor executor;
  private final int maxContentLength;
  private final long idleTimeoutNanos;
  private final LongSupplier clock;

  NettyChannelInitializer(final HandlerMap<NettyHandler> handlerMap,
                          final Executor executor,
                          final int maxContentLength,
                          final long idleTimeoutNanos,
                          final LongSupplier clock) {
    this.handlerMap = handlerMap;
    this.executor = executor;
    this.maxContentLength = maxContentLength;
    this.idleTimeoutNanos = idleTimeoutNanos;
    this.clock = clock;
  }

  @Override
  protected void initChannel(final Channel channel) {
    channel.pipeline().addLast(
        new HttpServerCodec(),
        new NettyRequestGate(maxContentLength, idleTimeoutNanos, clock),
        new NettyRequestAggregator(maxContentLength),
        new NettyController(handlerMap, executor)
    );
  }
}
