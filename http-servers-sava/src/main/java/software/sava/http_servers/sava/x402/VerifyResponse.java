package software.sava.http_servers.sava.x402;

import software.sava.core.accounts.PublicKey;
import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.nio.charset.StandardCharsets;

import static software.sava.rpc.json.PublicKeyEncoding.parseBase58Encoded;
import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

/// The response returned by a facilitator {@code /verify} endpoint.
///
/// @param isValid       whether the payment transaction passed all verification rules.
/// @param invalidReason a machine readable reason code (see {@link X402Errors}) when invalid, otherwise {@code null}.
/// @param payer         the address paying for the resource (the TransferChecked authority), when known.
public record VerifyResponse(boolean isValid, String invalidReason, PublicKey payer) {

  public static VerifyResponse valid(final PublicKey payer) {
    return new VerifyResponse(true, null, payer);
  }

  public static VerifyResponse invalid(final String reason, final PublicKey payer) {
    return new VerifyResponse(false, reason, payer);
  }

  public String toJson() {
    final var b = new StringBuilder(128);
    b.append("{\"isValid\":").append(isValid);
    b.append(",");
    JsonWrite.appendString(b, "invalidReason", invalidReason);
    b.append(",");
    JsonWrite.appendString(b, "payer", payer == null ? null : payer.toBase58());
    b.append('}');
    return b.toString();
  }

  public byte[] toJsonBytes() {
    return toJson().getBytes(StandardCharsets.UTF_8);
  }

  public static VerifyResponse parse(final JsonIterator ji) {
    final var parser = new Parser();
    ji.testObject(parser);
    return parser.create();
  }

  private static final class Parser implements FieldBufferPredicate {

    private boolean isValid;
    private String invalidReason;
    private PublicKey payer;

    private Parser() {
    }

    private VerifyResponse create() {
      return new VerifyResponse(isValid, invalidReason, payer);
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("isValid", buf, offset, len)) {
        isValid = ji.readBoolean();
      } else if (fieldEquals("invalidReason", buf, offset, len)) {
        invalidReason = ji.readString();
      } else if (fieldEquals("payer", buf, offset, len)) {
        payer = parseBase58Encoded(ji);
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
