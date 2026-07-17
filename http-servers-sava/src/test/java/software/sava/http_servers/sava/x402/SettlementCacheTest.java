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
  void wallClockClaimAndLookup() {
    final var cache = new SettlementCache();
    assertFalse(cache.isDuplicate("k"));
    assertTrue(cache.claim("k"));
    assertTrue(cache.isDuplicate("k"));
    assertFalse(cache.claim("k"));
    assertFalse(cache.isDuplicate("other"));
  }

  @Test
  void retentionBoundary() {
    final var cache = new SettlementCache(Duration.ofSeconds(120));
    final long window = Duration.ofSeconds(120).toMillis();
    assertTrue(cache.claim("k", 0L));
    // exactly at the window edge the claim is still held
    assertFalse(cache.claim("k", window));
    assertTrue(cache.isDuplicate("k", window));
    // one millisecond past it the entry has expired
    assertFalse(cache.isDuplicate("k", window + 1));
    assertTrue(cache.claim("k", window + 1));
  }

  @Test
  void evictionBoundary() {
    final var cache = new SettlementCache(Duration.ofSeconds(120));
    final long window = Duration.ofSeconds(120).toMillis();
    assertTrue(cache.claim("a", 0L));
    // claiming at the window edge must not evict the entry aged exactly the window
    assertTrue(cache.claim("b", window));
    assertEquals(2, cache.size());
    // one millisecond later "a" is expired and evicted by the next claim
    assertTrue(cache.claim("c", window + 1));
    assertEquals(2, cache.size());
    assertFalse(cache.isDuplicate("a", window + 1));
    assertTrue(cache.isDuplicate("b", window + 1));
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
