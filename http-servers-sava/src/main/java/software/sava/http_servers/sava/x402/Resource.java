package software.sava.http_servers.sava.x402;

import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

/// Describes the protected resource a payment grants access to.
public record Resource(String url, String description, String mimeType) {

  public static Resource parse(final JsonIterator ji) {
    final var parser = new Parser();
    ji.testObject(parser);
    return parser.create();
  }

  public void appendTo(final StringBuilder b) {
    b.append('{');
    JsonWrite.appendString(b, "url", url);
    b.append(',');
    JsonWrite.appendString(b, "description", description);
    b.append(',');
    JsonWrite.appendString(b, "mimeType", mimeType);
    b.append('}');
  }

  private static final class Parser implements FieldBufferPredicate {

    private String url;
    private String description;
    private String mimeType;

    private Parser() {
    }

    private Resource create() {
      return new Resource(url, description, mimeType);
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("url", buf, offset, len)) {
        url = ji.readString();
      } else if (fieldEquals("description", buf, offset, len)) {
        description = ji.readString();
      } else if (fieldEquals("mimeType", buf, offset, len)) {
        mimeType = ji.readString();
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
