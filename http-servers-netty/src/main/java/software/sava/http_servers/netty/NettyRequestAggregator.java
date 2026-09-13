package software.sava.http_servers.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;

/// The request aggregator with two explicit policies over Netty's.
///
/// A body past `maxContentLength` is answered `413` with `Connection: close`, and the
/// connection is closed once that answer is out — by [NettyRequestGate], which closes after
/// any response carrying that header, in request order. Netty's own `handleOversizedMessage`
/// chooses between closing and draining the rest of the body from the channel's `autoRead`
/// state at that instant, which the gate toggles for its own reasons; pinning the choice here
/// keeps the contract independent of that timing. Closing rather than draining bounds what an
/// oversized request can cost: the body is never read, so a client still sending it may see
/// the connection reset before it has read the `413`.
///
/// Expectations are not refused here. Netty's aggregator answers an unsupported `Expect` with
/// `417` and an `Expect: 100-continue` announcing a body over the limit with `413`, telling the
/// codec as it does so that the refused body is not coming; behind the gate, that would reach
/// the codec only at the refused request's turn, when the codec may stand on a later request's
/// body, so the gate makes both refusals itself the moment a head is decoded, and no head it
/// refuses ever arrives here. What this aggregator inherits is the `100 Continue` for an
/// `Expect: 100-continue` within the limit, written when the gate forwards the head — in turn.
/// A malformed head is exempt from that too: Netty would answer its expectation before its
/// decode failure (a `100` ahead of the `400`, or a `417` in place of it, the request then
/// never answered), whereas here its answer is what it would be with no expectation at all —
/// the controller's `400`, or, when the malformed head also declares a body over the limit,
/// this aggregator's `413` and close, which the inherited `decode` tests before it looks at
/// the decoder result.
final class NettyRequestAggregator extends HttpObjectAggregator {

  NettyRequestAggregator(final int maxContentLength) {
    super(maxContentLength);
  }

  @Override
  protected Object newContinueResponse(final HttpMessage start, final int maxContentLength, final ChannelPipeline pipeline) {
    // the gate has already refused every head this would answer with 417 or 413; the inherited
    // answer for a well-formed head is therefore the 100 Continue or nothing
    return start.decoderResult().isFailure()
        ? null
        : super.newContinueResponse(start, maxContentLength, pipeline);
  }

  @Override
  protected void handleOversizedMessage(final ChannelHandlerContext ctx, final HttpMessage oversized) {
    // the aggregator has already dropped the partial body and will discard the rest of it
    ctx.writeAndFlush(ResponseUtil.closeAfter(ResponseUtil.emptyResponse(HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE)));
  }
}
