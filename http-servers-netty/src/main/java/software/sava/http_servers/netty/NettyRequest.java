package software.sava.http_servers.netty;

import io.netty.buffer.ByteBufUtil;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaders;
import software.sava.http_servers.core.request.Request;

/// An immutable view of one decoded request. `SimpleChannelInboundHandler` releases the
/// [FullHttpRequest] the moment `channelRead0` returns, and its `content()` is a pooled
/// buffer that is gone after that, so the body bytes are copied here on the event loop —
/// before any offload — and the Netty message is never touched again. The headers are
/// kept by reference: `HttpHeaders` is a plain map with no tie to the buffer's lifecycle,
/// and the codec does not touch a request's headers after handing it on. The request's
/// version and keep-alive are not carried here: `NettyRequestGate` reads them off the
/// decoded head, where it decides the connection's persistence.
///
/// The request target is split at the first `?` exactly as it crossed the wire: [#path()]
/// is the raw request-target before it and [#query()] the raw text after it — `null` when
/// there is no `?`, and the empty string for a bare `?`, matching the JDK backend. An
/// absolute-form target (`GET http://host/p HTTP/1.1`) is not reduced to its path, so the
/// shared routing refuses it with the ambiguous-path 400 — as java-http does; the JDK and
/// Jetty servers reduce it to its path first.
final class NettyRequest implements Request {

  private final String method;
  private final String path;
  private final String query;
  private final boolean malformed;
  private final HttpHeaders headers;
  private final byte[] body;

  NettyRequest(final FullHttpRequest request) {
    this.method = request.method().name();
    final var uri = request.uri();
    final int queryStart = uri.indexOf('?');
    if (queryStart == -1) {
      this.path = uri;
      this.query = null;
    } else {
      this.path = uri.substring(0, queryStart);
      this.query = uri.substring(queryStart + 1);
    }
    this.malformed = request.decoderResult().isFailure();
    this.headers = request.headers();
    this.body = ByteBufUtil.getBytes(request.content());
  }

  @Override
  public String method() {
    return method;
  }

  @Override
  public String path() {
    return path;
  }

  @Override
  public String query() {
    return query;
  }

  @Override
  public String header(final String name) {
    // HttpHeaders lookups are case-insensitive; get returns the first value
    return headers.get(name);
  }

  @Override
  public byte[] body() {
    return body;
  }

  /// `true` when the codec could not parse the request (a header line without a colon, an
  /// over-long request line, an unparsable `Content-Length`); the decoder has discarded the
  /// rest of the stream, so the answer is 400 and the connection is closed.
  boolean malformed() {
    return malformed;
  }
}
