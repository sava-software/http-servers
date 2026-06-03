package software.sava.http_servers.sava.x402;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/// In-memory, time-evicting cache implementing the x402 "Duplicate Settlement Mitigation"
/// recommendation for the Solana {@code exact} scheme.
///
/// A facilitator should, after a payment verifies, attempt to {@link #claim(String)} the payment's
/// cache key (e.g. the Base64 transaction string or its message hash) before signing and submitting.
/// If the key was already claimed within the retention window the settlement must be rejected with
/// {@link X402Errors#DUPLICATE_SETTLEMENT}. Entries older than the retention window are evicted, since
/// the transaction's blockhash will have expired and it can no longer land on-chain.
///
/// This type is thread-safe.
public final class SettlementCache {

  /// Default retention window: ~twice the Solana blockhash lifetime.
  public static final Duration DEFAULT_RETENTION = Duration.ofSeconds(120);

  private final Map<String, Long> seen = new ConcurrentHashMap<>();
  private final long retentionMillis;

  public SettlementCache() {
    this(DEFAULT_RETENTION);
  }

  public SettlementCache(final Duration retention) {
    this.retentionMillis = retention.toMillis();
  }

  /// Atomically claim a settlement key.
  ///
  /// @param key a stable identifier for the payment transaction.
  /// @return {@code true} if the key was newly claimed (the caller may proceed to settle);
  ///         {@code false} if it was already claimed within the retention window (a duplicate).
  public boolean claim(final String key) {
    return claim(key, System.currentTimeMillis());
  }

  boolean claim(final String key, final long nowMillis) {
    evictExpired(nowMillis);
    final var previous = seen.putIfAbsent(key, nowMillis);
    if (previous == null) {
      return true;
    }
    if (nowMillis - previous > retentionMillis) {
      // The previous claim has expired; replace it and allow this attempt.
      seen.put(key, nowMillis);
      return true;
    }
    return false;
  }

  /// @return whether the key is currently claimed (present and not expired).
  public boolean isDuplicate(final String key) {
    return isDuplicate(key, System.currentTimeMillis());
  }

  boolean isDuplicate(final String key, final long nowMillis) {
    final var seenAt = seen.get(key);
    return seenAt != null && (nowMillis - seenAt) <= retentionMillis;
  }

  int size() {
    return seen.size();
  }

  private void evictExpired(final long nowMillis) {
    seen.entrySet().removeIf(e -> nowMillis - e.getValue() > retentionMillis);
  }
}
