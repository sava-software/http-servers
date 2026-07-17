package software.sava.http_servers.sava.handlers;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static software.sava.http_servers.sava.handlers.HandlerUtil.parsePublicKeyParam;
import static software.sava.http_servers.sava.handlers.HandlerUtil.parsePublicKeyParams;

final class HandlerUtilTests {

  private static PublicKey key(final int seed) {
    final byte[] b = new byte[PublicKey.PUBLIC_KEY_LENGTH];
    Arrays.fill(b, (byte) seed);
    return PublicKey.createPubKey(b);
  }

  private static final PublicKey KEY_1 = key(1);
  private static final PublicKey KEY_2 = key(2);
  private static final PublicKey KEY_3 = key(3);
  private static final PublicKey DEFAULT = key(9);

  @Test
  void parseSingleKeyParam() {
    assertEquals(KEY_1, parsePublicKeyParam("owner=" + KEY_1.toBase58(), "owner="));
    assertEquals(KEY_1, parsePublicKeyParam("a=1&owner=" + KEY_1.toBase58() + "&z=2", "owner="));
  }

  @Test
  void parseSingleKeyParamDefaults() {
    assertNull(parsePublicKeyParam("a=1", "owner="));
    assertNull(parsePublicKeyParam(null, "owner="));
    assertEquals(DEFAULT, parsePublicKeyParam("a=1", "owner=", DEFAULT));
    assertEquals(DEFAULT, parsePublicKeyParam(null, "owner=", DEFAULT));
  }

  @Test
  void parseSingleKeyParamRejectsSubstringMatches() {
    assertEquals(DEFAULT, parsePublicKeyParam("newowner=" + KEY_1.toBase58(), "owner=", DEFAULT));
  }

  @Test
  void parseKeyListSingle() {
    assertEquals(List.of(KEY_1), parsePublicKeyParams("keys=" + KEY_1.toBase58(), "keys="));
    assertEquals(List.of(KEY_1), parsePublicKeyParams("keys=" + KEY_1.toBase58() + "&x=1", "keys="));
    assertEquals(List.of(KEY_1), parsePublicKeyParams("x=1&keys=" + KEY_1.toBase58(), "keys="));
  }

  @Test
  void parseKeyListMulti() {
    final var value = KEY_1.toBase58() + ',' + KEY_2.toBase58() + ',' + KEY_3.toBase58();
    assertEquals(List.of(KEY_1, KEY_2, KEY_3), parsePublicKeyParams("keys=" + value, "keys="));
    assertEquals(List.of(KEY_1, KEY_2, KEY_3), parsePublicKeyParams("keys=" + value + "&x=1", "keys="));
    assertEquals(List.of(KEY_1, KEY_2, KEY_3), parsePublicKeyParams("x=1&keys=" + value, "keys="));
  }

  @Test
  void parseKeyListMissing() {
    assertTrue(parsePublicKeyParams("x=1", "keys=").isEmpty());
    assertTrue(parsePublicKeyParams(null, "keys=").isEmpty());
  }

  @Test
  void parseKeyListRejectsSubstringMatches() {
    assertTrue(parsePublicKeyParams("monkeys=" + KEY_1.toBase58(), "keys=").isEmpty());
  }

  @Test
  void parseKeyListIgnoresCommaInLaterParam() {
    // the comma belongs to the next parameter; this is still a single-key value
    final var query = "keys=" + KEY_1.toBase58() + "&list=a,b";
    assertEquals(List.of(KEY_1), parsePublicKeyParams(query, "keys="));
  }

  @Test
  void parseKeyListToleratesTrailingComma() {
    assertEquals(List.of(KEY_1, KEY_2), parsePublicKeyParams(
        "keys=" + KEY_1.toBase58() + ',' + KEY_2.toBase58() + ',', "keys="));
  }
}
