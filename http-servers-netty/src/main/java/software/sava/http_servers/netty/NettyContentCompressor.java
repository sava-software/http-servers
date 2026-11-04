package software.sava.http_servers.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.compression.StandardCompressionOptions;
import io.netty.handler.codec.http.HttpContentCompressor;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.util.ReferenceCountUtil;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/// Per-connection gzip negotiation. Placed before the request gate so
/// that the gate's final-response promise completes only after all encoded content has
/// been written, including the gzip trailer. Netty owns framing and encoder cleanup.
final class NettyContentCompressor extends HttpContentCompressor {

  // Jetty's GzipCompression defaults to 32 bytes; already compressed media and SSE are
  // excluded by its CompressionConfig defaults. Ranges and multipart representations also
  // retain their original bytes and framing.
  private static final Set<String> EXCLUDED_TYPES = Set.of(
      "application/compress", "application/zip", "application/gzip", "application/x-bzip2",
      "application/brotli", "application/x-br", "application/x-xz", "application/x-rar-compressed",
      "application/vnd.bzip3", "application/zstd", "text/event-stream"
  );
  private static final Pattern QUALITY = Pattern.compile("(?:0(?:\\.[0-9]{0,3})?|1(?:\\.0{0,3})?)");

  private int pendingRequests;

  NettyContentCompressor() {
    // The request gate already queues pipelined heads. Do not impose the compressor's
    // unrelated default depth of 128 on a single decoded socket read.
    super(32, Integer.MAX_VALUE, StandardCompressionOptions.gzip());
  }

  @Override
  protected void decode(final ChannelHandlerContext ctx, final HttpRequest request, final List<Object> out)
      throws Exception {
    super.decode(ctx, request, out);
    ++pendingRequests;
  }

  @Override
  protected void encode(final ChannelHandlerContext ctx, final HttpObject msg, final List<Object> out)
      throws Exception {
    if (msg instanceof HttpResponse response && response.status().code() >= 200) {
      final var headers = response.headers();
      // Identity responses vary too: a shared cache must not serve that variant to a
      // gzip-capable peer without consulting the request's Accept-Encoding.
      if (!headers.containsValue(HttpHeaderNames.VARY, "*", true)
          && !headers.containsValue(HttpHeaderNames.VARY, HttpHeaderNames.ACCEPT_ENCODING, true)) {
        headers.add(HttpHeaderNames.VARY, HttpHeaderNames.ACCEPT_ENCODING);
      }
      // The controller can answer a transport failure before any request head arrived.
      // There is no peer encoding to negotiate then; Netty's encoder otherwise refuses
      // the final response as "more responses than requests" and loses that 500.
      if (pendingRequests == 0) {
        out.add(ReferenceCountUtil.retain(msg));
        return;
      }
      --pendingRequests;
    }
    super.encode(ctx, msg, out);
  }

  @Override
  protected Result beginEncode(final HttpResponse response, final String acceptEncoding) throws Exception {
    final int status = response.status().code();
    final var headers = response.headers();
    if (status >= 300 || status == 205 || headers.contains(HttpHeaderNames.CONTENT_RANGE)) {
      return null;
    }
    final String contentType = headers.get(HttpHeaderNames.CONTENT_TYPE);
    if (contentType != null) {
      final String mime = contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
      if (mime.startsWith("image/") || mime.startsWith("audio/") || mime.startsWith("video/")
          || mime.startsWith("font/") || mime.startsWith("multipart/") || EXCLUDED_TYPES.contains(mime)) {
        return null;
      }
    }
    final Result result = super.beginEncode(response, acceptEncoding);
    if (result != null) {
      // A strong validator describes exact representation bytes. Compression changes
      // those bytes, while a weak validator can still describe their shared semantics.
      final String etag = headers.get(HttpHeaderNames.ETAG);
      if (etag != null && etag.startsWith("\"")) {
        headers.set(HttpHeaderNames.ETAG, "W/" + etag);
      }
    }
    return result;
  }

  @Override
  protected String determineEncoding(final String acceptEncoding) {
    // Match complete coding tokens. Netty's general-purpose matcher accepts substrings
    // (e.g. "notgzip"); only gzip and its historical alias are configured here.
    double gzip = -1;
    double wildcard = -1;
    for (final String field : acceptEncoding.split(",")) {
      final String[] parameters = field.split(";", -1);
      final String coding = parameters[0].strip();
      if (!coding.equalsIgnoreCase("gzip") && !coding.equalsIgnoreCase("x-gzip") && !coding.equals("*")) {
        continue;
      }
      double quality = 1;
      if (parameters.length > 1) {
        final String parameter = parameters[1].strip();
        if (parameters.length != 2 || !parameter.regionMatches(true, 0, "q=", 0, 2)
            || !QUALITY.matcher(parameter.substring(2)).matches()) {
          quality = 0;
        } else {
          quality = Double.parseDouble(parameter.substring(2));
        }
      }
      if (coding.equals("*")) {
        wildcard = Math.max(wildcard, quality);
      } else {
        gzip = Math.max(gzip, quality);
      }
    }
    return (gzip >= 0 ? gzip : wildcard) > 0 ? "gzip" : null;
  }
}
