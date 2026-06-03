package software.sava.http_servers.sava.x402;

import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

/// The body a resource server returns with an HTTP 402 response, signaling that payment is required.
///
/// @param x402Version the protocol version.
/// @param error        a human readable message explaining why payment is required.
/// @param resource     the resource the payment grants access to (may be {@code null}).
/// @param accepts      the list of acceptable payment requirements; a client picks one to satisfy.
public record PaymentRequired(int x402Version,
                              String error,
                              Resource resource,
                              List<PaymentRequirements> accepts) {

  public String toJson() {
    final var b = new StringBuilder(256);
    b.append("{\"x402Version\":").append(x402Version);
    b.append(',');
    JsonWrite.appendString(b, "error", error);
    if (resource != null) {
      b.append(",\"resource\":");
      resource.appendTo(b);
    }
    b.append(",\"accepts\":[");
    for (int i = 0; i < accepts.size(); ++i) {
      if (i > 0) {
        b.append(',');
      }
      accepts.get(i).appendTo(b);
    }
    b.append("]}");
    return b.toString();
  }

  public byte[] toJsonBytes() {
    return toJson().getBytes(StandardCharsets.UTF_8);
  }

  public static PaymentRequired parse(final JsonIterator ji) {
    final var parser = new Parser();
    ji.testObject(parser);
    return parser.create();
  }

  private static final class Parser implements FieldBufferPredicate {

    private int x402Version;
    private String error;
    private Resource resource;
    private List<PaymentRequirements> accepts = List.of();

    private Parser() {
    }

    private PaymentRequired create() {
      return new PaymentRequired(x402Version, error, resource, accepts);
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("x402Version", buf, offset, len)) {
        x402Version = ji.readInt();
      } else if (fieldEquals("error", buf, offset, len)) {
        error = ji.readString();
      } else if (fieldEquals("resource", buf, offset, len)) {
        resource = Resource.parse(ji);
      } else if (fieldEquals("accepts", buf, offset, len)) {
        final var list = new ArrayList<PaymentRequirements>();
        while (ji.readArray()) {
          list.add(PaymentRequirements.parse(ji));
        }
        accepts = list;
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
