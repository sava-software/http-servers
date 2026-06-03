package software.sava.http_servers.sava.x402;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

final class SettlementCacheTest {

  @Test
  void firstClaimSucceedsDuplicateRejected() {
    final var cache = new SettlementCache(Duration.ofSeconds(120));
    assertTrue(cache.claim("tx-key", 0L));
    assertFalse(cache.claim("tx-key", 1_000L));
    assertTrue(cache.isDuplicate("tx-key", 1_000L));
  }

  @Test
  void differentKeysAreIndependent() {
    final var cache = new SettlementCache(Duration.ofSeconds(120));
    assertTrue(cache.claim("a", 0L));
    assertTrue(cache.claim("b", 0L));
  }

  @Test
  void expiredEntryCanBeReclaimedAndEvicted() {
    final var cache = new SettlementCache(Duration.ofSeconds(120));
    assertTrue(cache.claim("tx-key", 0L));
    // After the retention window the key expires and may be reclaimed.
    final long afterWindow = Duration.ofSeconds(121).toMillis();
    assertFalse(cache.isDuplicate("tx-key", afterWindow));
    assertTrue(cache.claim("tx-key", afterWindow));
    assertEquals(1, cache.size());
  }
}
