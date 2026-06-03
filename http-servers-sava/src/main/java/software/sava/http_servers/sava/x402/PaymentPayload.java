package software.sava.http_servers.sava.x402;

import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.util.Base64;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

/// The x402 {@code PaymentPayload} sent by a client for the Solana {@code exact} scheme.
///
/// The {@code transaction} is the Base64 encoded, serialized, partially-signed versioned Solana
/// transaction (i.e. it is still missing the facilitator's fee-payer signature).
public record PaymentPayload(int x402Version,
                             Resource resource,
                             PaymentRequirements accepted,
                             String transaction) {

  /// Parse a payload from its raw JSON representation.
  public static PaymentPayload parse(final JsonIterator ji) {
    final var parser = new Parser();
    ji.testObject(parser);
    return parser.create();
  }

  /// Parse a payload from a JSON string.
  public static PaymentPayload parse(final String json) {
    return parse(JsonIterator.parse(json));
  }

  /// Parse a payload from the Base64 encoded value of the {@code X-PAYMENT} HTTP header.
  public static PaymentPayload fromBase64Header(final String header) {
    final byte[] json = Base64.getDecoder().decode(header);
    return parse(JsonIterator.parse(json));
  }

  /// @return the decoded, serialized transaction bytes.
  public byte[] transactionBytes() {
    return Base64.getDecoder().decode(transaction);
  }

  private static final class Parser implements FieldBufferPredicate {

    private int x402Version;
    private Resource resource;
    private PaymentRequirements accepted;
    private String transaction;

    private Parser() {
    }

    private PaymentPayload create() {
      return new PaymentPayload(x402Version, resource, accepted, transaction);
    }

    private final FieldBufferPredicate payloadParser = (buf, offset, len, ji) -> {
      if (fieldEquals("transaction", buf, offset, len)) {
        transaction = ji.readString();
      } else {
        ji.skip();
      }
      return true;
    };

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("x402Version", buf, offset, len)) {
        x402Version = ji.readInt();
      } else if (fieldEquals("resource", buf, offset, len)) {
        resource = Resource.parse(ji);
      } else if (fieldEquals("accepted", buf, offset, len)) {
        accepted = PaymentRequirements.parse(ji);
      } else if (fieldEquals("payload", buf, offset, len)) {
        ji.testObject(payloadParser);
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
