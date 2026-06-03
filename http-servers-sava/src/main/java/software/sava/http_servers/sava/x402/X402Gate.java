package software.sava.http_servers.sava.x402;

import software.sava.core.accounts.SolanaAccounts;
import software.sava.http_servers.core.request.Request;
import software.sava.http_servers.core.response.HttpResponse;
import software.sava.http_servers.core.response.QueryHandler;

import java.util.List;

/// A resource-server {@link QueryHandler} wrapper that enforces the x402 protocol for the Solana
/// (SVM) {@code exact} scheme in front of a protected handler.
///
/// On each request it:
///
/// 1. Reads the {@code X-PAYMENT} request header. When it is missing it responds with HTTP 402 and a
///    {@link PaymentRequired} body listing the acceptable {@link PaymentRequirements}.
/// 2. Otherwise it decodes the {@link PaymentPayload} and runs the facilitator {@code /verify}
///    rules through {@link SvmExactVerifier}. A failed verification yields another HTTP 402 carrying
///    the {@code invalidReason}.
/// 3. When verification passes it delegates to the protected handler and attaches an
///    {@code X-PAYMENT-RESPONSE} header built from a {@link SettlementResponse} reporting the payer.
///
/// When an {@link SvmExactSettler} is supplied the gate additionally performs live on-chain settlement
/// (the facilitator {@code /settle} step: fee-payer co-signing, RPC submission and confirmation). In
/// that mode the protected resource is served only after the payment transaction confirms, and the
/// attached {@code X-PAYMENT-RESPONSE} carries the real transaction signature. Without a settler the
/// gate falls back to verification only, and its settlement response carries no transaction signature.
public final class X402Gate implements QueryHandler {

  private final QueryHandler protectedHandler;
  private final PaymentRequirements requirements;
  private final Resource resource;
  private final SvmExactVerifier verifier;
  private final SvmExactSettler settler;

  public X402Gate(final QueryHandler protectedHandler,
                  final PaymentRequirements requirements,
                  final Resource resource,
                  final SvmExactVerifier verifier,
                  final SvmExactSettler settler) {
    this.protectedHandler = protectedHandler;
    this.requirements = requirements;
    this.resource = resource;
    this.verifier = verifier;
    this.settler = settler;
  }

  public X402Gate(final QueryHandler protectedHandler,
                  final PaymentRequirements requirements,
                  final Resource resource,
                  final SvmExactVerifier verifier) {
    this(protectedHandler, requirements, resource, verifier, null);
  }

  public X402Gate(final QueryHandler protectedHandler,
                  final PaymentRequirements requirements,
                  final Resource resource) {
    this(protectedHandler, requirements, resource, new SvmExactVerifier(SolanaAccounts.MAIN_NET), null);
  }

  /// Create a gate that settles payments on-chain through the given {@link SvmExactSettler}.
  public X402Gate(final QueryHandler protectedHandler,
                  final PaymentRequirements requirements,
                  final Resource resource,
                  final SvmExactSettler settler) {
    this(protectedHandler, requirements, resource, new SvmExactVerifier(SolanaAccounts.MAIN_NET), settler);
  }

  @Override
  public HttpResponse httpResponse(final String path, final String query) {
    // The request headers are required to read the X-PAYMENT payload, so without them the only
    // correct response is to ask the client for payment.
    return paymentRequired("X-PAYMENT header is required");
  }

  @Override
  public HttpResponse httpResponse(final Request request) {
    final var header = request.header(X402.PAYMENT_HEADER);
    if (header == null || header.isBlank()) {
      return paymentRequired("X-PAYMENT header is required");
    }

    final PaymentPayload payload;
    try {
      payload = PaymentPayload.fromBase64Header(header);
    } catch (final RuntimeException e) {
      return paymentRequired(X402Errors.INVALID_PAYLOAD_TRANSACTION);
    }

    final SettlementResponse settlement;
    if (settler != null) {
      // Settle the payment on-chain; only serve the resource once it confirms.
      settlement = settler.settle(payload, requirements);
      if (!settlement.success()) {
        return paymentRequired(settlement.errorReason());
      }
    } else {
      final var verifyResponse = verifier.verify(payload, requirements);
      if (!verifyResponse.isValid()) {
        return paymentRequired(verifyResponse.invalidReason());
      }
      settlement = SettlementResponse.success(null, requirements.network(), verifyResponse.payer());
    }

    final var response = protectedHandler.httpResponse(request);
    return response.withHeader(X402.PAYMENT_RESPONSE_HEADER, settlement.toBase64Header());
  }

  private HttpResponse paymentRequired(final String error) {
    final var body = new PaymentRequired(X402.X402_VERSION, error, resource, List.of(requirements));
    return HttpResponse.json(402, body.toJson());
  }
}
