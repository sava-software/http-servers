package software.sava.http_servers.netty;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;

/// The request aggregator with one explicit policy: a body past `maxContentLength` is
/// answered `413` with `Connection: close`, and the connection is closed once that answer is
/// out — by [NettyRequestGate], which closes after any response carrying that header, in
/// request order. Netty's own `handleOversizedMessage` chooses between closing and draining
/// the rest of the body from the channel's `autoRead` state at that instant, which the gate
/// toggles for its own reasons; pinning the choice here keeps the contract independent of
/// that timing. Closing rather than draining bounds what an oversized request can cost: the
/// body is never read, so a client still sending it may see the connection reset before it
/// has read the `413`.
///
/// The `Expect: 100-continue` refusal is untouched: there the `413` precedes any body, the
/// aggregator sends it without a close header and the connection stays open.
final class NettyRequestAggregator extends HttpObjectAggregator {

  NettyRequestAggregator(final int maxContentLength) {
    super(maxContentLength);
  }

  @Override
  protected void handleOversizedMessage(final ChannelHandlerContext ctx, final HttpMessage oversized) {
    // the aggregator has already dropped the partial body and will discard the rest of it
    final var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE, Unpooled.EMPTY_BUFFER);
    HttpUtil.setContentLength(response, 0);
    ctx.writeAndFlush(ResponseUtil.closeAfter(response));
  }
}
