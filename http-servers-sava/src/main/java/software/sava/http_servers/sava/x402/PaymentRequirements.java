package software.sava.http_servers.sava.x402;

import software.sava.core.accounts.PublicKey;
import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import static software.sava.rpc.json.PublicKeyEncoding.parseBase58Encoded;
import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

/// The x402 {@code PaymentRequirements} for the Solana {@code exact} scheme.
///
/// The scheme-specific {@code feePayer} and optional {@code memo} live under the {@code extra} object
/// in the wire format, but are flattened here for convenience.
public record PaymentRequirements(String scheme,
                                  String network,
                                  String amount,
                                  PublicKey asset,
                                  PublicKey payTo,
                                  int maxTimeoutSeconds,
                                  PublicKey feePayer,
                                  String memo) {

  /// @return the {@code amount} parsed as an unsigned long of the token's smallest unit.
  public long amountAsLong() {
    return Long.parseUnsignedLong(amount);
  }

  public static PaymentRequirements parse(final JsonIterator ji) {
    final var parser = new Parser();
    ji.testObject(parser);
    return parser.create();
  }

  public void appendTo(final StringBuilder b) {
    b.append('{');
    JsonWrite.appendString(b, "scheme", scheme);
    b.append(',');
    JsonWrite.appendString(b, "network", network);
    b.append(',');
    JsonWrite.appendString(b, "amount", amount);
    b.append(',');
    JsonWrite.appendString(b, "asset", asset == null ? null : asset.toBase58());
    b.append(',');
    JsonWrite.appendString(b, "payTo", payTo == null ? null : payTo.toBase58());
    b.append(",\"maxTimeoutSeconds\":").append(maxTimeoutSeconds);
    b.append(",\"extra\":{");
    JsonWrite.appendString(b, "feePayer", feePayer == null ? null : feePayer.toBase58());
    if (memo != null) {
      b.append(',');
      JsonWrite.appendString(b, "memo", memo);
    }
    b.append("}}");
  }

  private static final class Parser implements FieldBufferPredicate {

    private String scheme;
    private String network;
    private String amount;
    private PublicKey asset;
    private PublicKey payTo;
    private int maxTimeoutSeconds;
    private PublicKey feePayer;
    private String memo;

    private Parser() {
    }

    private PaymentRequirements create() {
      return new PaymentRequirements(scheme, network, amount, asset, payTo, maxTimeoutSeconds, feePayer, memo);
    }

    private final FieldBufferPredicate extraParser = (buf, offset, len, ji) -> {
      if (fieldEquals("feePayer", buf, offset, len)) {
        feePayer = parseBase58Encoded(ji);
      } else if (fieldEquals("memo", buf, offset, len)) {
        memo = ji.readString();
      } else {
        ji.skip();
      }
      return true;
    };

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("scheme", buf, offset, len)) {
        scheme = ji.readString();
      } else if (fieldEquals("network", buf, offset, len)) {
        network = ji.readString();
      } else if (fieldEquals("amount", buf, offset, len)) {
        amount = ji.readString();
      } else if (fieldEquals("asset", buf, offset, len)) {
        asset = parseBase58Encoded(ji);
      } else if (fieldEquals("payTo", buf, offset, len)) {
        payTo = parseBase58Encoded(ji);
      } else if (fieldEquals("maxTimeoutSeconds", buf, offset, len)) {
        maxTimeoutSeconds = ji.readInt();
      } else if (fieldEquals("extra", buf, offset, len)) {
        ji.testObject(extraParser);
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
