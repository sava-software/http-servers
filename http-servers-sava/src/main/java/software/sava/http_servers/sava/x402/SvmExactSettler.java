package software.sava.http_servers.sava.x402;

import software.sava.core.accounts.Signer;
import software.sava.core.accounts.SolanaAccounts;
import software.sava.core.tx.Transaction;
import software.sava.core.tx.TransactionSkeleton;
import software.sava.rpc.json.http.client.SolanaRpcClient;
import software.sava.rpc.json.http.request.Commitment;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

/// Implements the x402 facilitator {@code /settle} step for the Solana (SVM) {@code exact} scheme.
///
/// Whereas {@link SvmExactVerifier} only validates the client-provided, partially-signed transaction,
/// this settler completes the payment on-chain. It mirrors the reference Go facilitator flow:
///
/// 1. Re-run {@link SvmExactVerifier#verify} so settlement never proceeds on an invalid payment.
/// 2. Confirm the transaction's fee payer (account index 0) matches the {@code feePayer} advertised in
///    the {@link PaymentRequirements} and that this facilitator holds the matching {@link Signer}.
/// 3. Reject duplicate submissions via a {@link SettlementCache}, keyed on a hash of the transaction
///    message bytes (immune to the still-mutable fee-payer signature slot).
/// 4. Co-sign the transaction as the fee payer, filling signature slot 0 in place.
/// 5. Submit the fully-signed transaction through an RPC node and wait for confirmation.
///
/// Network interaction is abstracted behind {@link TransactionSubmitter} so the settler can be unit
/// tested offline; {@link TransactionSubmitter#rpc} provides the production, {@link SolanaRpcClient}
/// backed implementation.
public final class SvmExactSettler {

  private final SvmExactVerifier verifier;
  private final Signer feePayer;
  private final TransactionSubmitter submitter;
  private final SettlementCache settlementCache;

  public SvmExactSettler(final SvmExactVerifier verifier,
                         final Signer feePayer,
                         final TransactionSubmitter submitter,
                         final SettlementCache settlementCache) {
    this.verifier = verifier;
    this.feePayer = feePayer;
    this.submitter = submitter;
    this.settlementCache = settlementCache;
  }

  /// Convenience constructor wiring a {@link SolanaRpcClient} backed {@link TransactionSubmitter} with
  /// the {@code confirmed} commitment level and sensible polling defaults.
  public SvmExactSettler(final SolanaAccounts accounts,
                         final Signer feePayer,
                         final SolanaRpcClient rpcClient) {
    this(new SvmExactVerifier(accounts),
        feePayer,
        TransactionSubmitter.rpc(rpcClient, Commitment.CONFIRMED, Duration.ofSeconds(60), Duration.ofMillis(500)),
        new SettlementCache());
  }

  /// Verify, co-sign, submit and confirm the payment described by {@code payload}.
  ///
  /// @return a successful {@link SettlementResponse} carrying the Base58 transaction signature, or a
  ///         failure response whose {@code errorReason} explains why settlement did not complete.
  public SettlementResponse settle(final PaymentPayload payload, final PaymentRequirements requirements) {
    final var network = requirements == null ? null : requirements.network();

    // Step 1: never settle a payment that does not verify.
    final var verifyResponse = verifier.verify(payload, requirements);
    if (!verifyResponse.isValid()) {
      return SettlementResponse.failure(verifyResponse.invalidReason(), network, verifyResponse.payer());
    }
    final var payer = verifyResponse.payer();

    final byte[] txBytes;
    try {
      txBytes = payload.transactionBytes();
    } catch (final RuntimeException e) {
      return SettlementResponse.failure(X402Errors.INVALID_PAYLOAD_TRANSACTION, network, payer);
    }

    final TransactionSkeleton skeleton;
    try {
      skeleton = TransactionSkeleton.deserializeSkeleton(txBytes);
    } catch (final RuntimeException e) {
      return SettlementResponse.failure(X402Errors.INVALID_PAYLOAD_TRANSACTION, network, payer);
    }

    // Step 2: the transaction's fee payer (signer slot 0) must match the advertised feePayer and this
    // facilitator must hold the matching signing key.
    final var txFeePayer = skeleton.feePayer();
    if (txFeePayer == null
        || !txFeePayer.equals(requirements.feePayer())
        || !txFeePayer.equals(feePayer.publicKey())) {
      return SettlementResponse.failure(X402Errors.FEE_PAYER_MISMATCH, network, payer);
    }

    // Step 3: reject duplicate settlements, keyed on the immutable message bytes.
    final int numSignatures = Byte.toUnsignedInt(txBytes[0]);
    final int msgOffset = 1 + (numSignatures * Transaction.SIGNATURE_LENGTH);
    final var cacheKey = messageHash(txBytes, msgOffset, txBytes.length - msgOffset);
    if (!settlementCache.claim(cacheKey)) {
      return SettlementResponse.failure(X402Errors.DUPLICATE_SETTLEMENT, network, payer);
    }

    // Step 4: co-sign as the fee payer, filling signature slot 0 in place.
    feePayer.sign(txBytes, msgOffset, txBytes.length - msgOffset, 1);
    final var base64SignedTx = Base64.getEncoder().encodeToString(txBytes);

    // Step 5: submit and confirm on-chain.
    final String signature;
    try {
      signature = submitter.send(base64SignedTx);
    } catch (final RuntimeException e) {
      return SettlementResponse.failure(X402Errors.TRANSACTION_FAILED, network, payer);
    }
    try {
      submitter.confirm(signature);
    } catch (final RuntimeException e) {
      return SettlementResponse.failure(X402Errors.TRANSACTION_CONFIRMATION_FAILED, signature, network, payer);
    }

    return SettlementResponse.success(signature, network, payer);
  }

  private static String messageHash(final byte[] data, final int offset, final int length) {
    final MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    digest.update(data, offset, length);
    return Base64.getEncoder().encodeToString(digest.digest());
  }

  /// Submits a fully-signed transaction to the network and waits for it to confirm.
  ///
  /// Splitting submission from confirmation lets the settler report the distinct
  /// {@link X402Errors#TRANSACTION_FAILED} and {@link X402Errors#TRANSACTION_CONFIRMATION_FAILED}
  /// reasons, and keeps the settler unit testable without a live RPC node.
  public interface TransactionSubmitter {

    /// Broadcast the Base64 encoded, fully-signed transaction.
    ///
    /// @return the Base58 transaction signature.
    /// @throws RuntimeException if the transaction could not be submitted.
    String send(String base64SignedTransaction);

    /// Block until the given transaction signature reaches the desired commitment level.
    ///
    /// @throws RuntimeException if the transaction failed or could not be confirmed in time.
    void confirm(String signature);

    /// @return an implementation backed by a {@link SolanaRpcClient}.
    static TransactionSubmitter rpc(final SolanaRpcClient rpcClient,
                                    final Commitment commitment,
                                    final Duration confirmationTimeout,
                                    final Duration pollInterval) {
      return new RpcTransactionSubmitter(rpcClient, commitment, confirmationTimeout, pollInterval);
    }
  }

  private record RpcTransactionSubmitter(SolanaRpcClient rpcClient,
                                         Commitment commitment,
                                         Duration confirmationTimeout,
                                         Duration pollInterval) implements TransactionSubmitter {

    @Override
    public String send(final String base64SignedTransaction) {
      return rpcClient.sendTransaction(base64SignedTransaction).join();
    }

    @Override
    public void confirm(final String signature) {
      final var signatures = List.of(signature);
      final long deadline = System.currentTimeMillis() + confirmationTimeout.toMillis();
      final long pollMillis = Math.max(1L, pollInterval.toMillis());
      do {
        final var statuses = rpcClient.getSignatureStatuses(signatures).join();
        final var status = statuses == null ? null : statuses.get(signature);
        if (status != null && !status.nil()) {
          if (status.error() != null) {
            throw new IllegalStateException("transaction failed: " + status.error());
          }
          if (reached(status.confirmationStatus())) {
            return;
          }
        }
        try {
          Thread.sleep(pollMillis);
        } catch (final InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("interrupted while confirming transaction", e);
        }
      } while (System.currentTimeMillis() < deadline);
      throw new IllegalStateException("timed out confirming transaction " + signature);
    }

    private boolean reached(final Commitment status) {
      return status != null && rank(status) >= rank(commitment);
    }

    private static int rank(final Commitment commitment) {
      return switch (commitment) {
        case PROCESSED -> 1;
        case CONFIRMED -> 2;
        case FINALIZED -> 3;
      };
    }
  }
}
