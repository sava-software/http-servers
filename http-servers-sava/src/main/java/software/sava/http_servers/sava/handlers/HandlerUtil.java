package software.sava.http_servers.sava.handlers;

import software.sava.core.accounts.PublicKey;

import java.util.ArrayList;
import java.util.List;

import static software.sava.http_servers.core.handlers.HandlerUtil.parseParam;

public class HandlerUtil {

  public static PublicKey parsePublicKeyParam(final String query, final String param, final PublicKey defaultValue) {
    if (query == null) {
      return defaultValue;
    } else {
      int index = query.indexOf(param);
      return index < 0 ? defaultValue : PublicKey.fromBase58Encoded(parseParam(query, index, param));
    }
  }

  public static PublicKey parsePublicKeyParam(final String query, final String param) {
    return parsePublicKeyParam(query, param, null);
  }

  private static final List<PublicKey> NO_PARAMS = List.of();

  public static List<PublicKey> parsePublicKeyParams(final String query, final String param) {
    if (query == null) {
      return NO_PARAMS;
    } else {
      int from = query.indexOf(param);
      if (from < 0) {
        return NO_PARAMS;
      }
      from += param.length();
      int end = query.indexOf('&', from + 1);
      if (end < 0) {
        end = query.length();
      }
      int nextComma = query.indexOf(',', from + PublicKey.PUBLIC_KEY_LENGTH);
      if (nextComma < 0 || nextComma > end) {
        final var keyString = query.substring(from, end);
        return List.of(PublicKey.fromBase58Encoded(keyString));
      } else {
        final char[] chars = new char[end - from];
        query.getChars(from, end, chars, 0);
        nextComma -= from;
        var key = PublicKey.fromBase58Encoded(chars, 0, nextComma);
        final var keys = new ArrayList<PublicKey>();
        keys.add(key);
        from = nextComma + 1;
        do {
          nextComma = from + PublicKey.PUBLIC_KEY_LENGTH;
          for (; nextComma < chars.length; ++nextComma) {
            if (chars[nextComma] == ',') {
              break;
            }
          }
          keys.add(PublicKey.fromBase58Encoded(chars, from, nextComma - from));
          from = nextComma + 1;
        } while (from < chars.length);
        return keys;
      }
    }
  }

  private HandlerUtil() {
  }
}
