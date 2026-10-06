package tacto.domain

import java.time.Instant

enum class PaymentMethod { CARD, QR }

/** Estados posibles de un pago. Tarjeta y QR comparten tabla pero recorren caminos distintos. */
enum class PaymentStatus {
    CREATED,
    PROCESSING, AUTHORIZED, CAPTURED,          // camino de tarjeta
    AWAITING_PAYMENT, SUCCEEDED, EXPIRED,      // camino de QR
    FAILED, CANCELED, REFUNDED;

    fun canTransitionTo(next: PaymentStatus): Boolean = next in ALLOWED.getValue(this)
}

/**
 * Tabla de transiciones válidas. Todo lo que no esté aquí está PROHIBIDO.
 *
 *  Tarjeta: CREATED -> PROCESSING -> AUTHORIZED -> CAPTURED -> REFUNDED
 *  QR:      CREATED -> AWAITING_PAYMENT -> SUCCEEDED -> REFUNDED
 *                                      \-> EXPIRED
 */
private val ALLOWED: Map<PaymentStatus, Set<PaymentStatus>> = mapOf(
    PaymentStatus.CREATED to setOf(
        PaymentStatus.PROCESSING, PaymentStatus.AWAITING_PAYMENT, PaymentStatus.FAILED, PaymentStatus.CANCELED,
    ),
    PaymentStatus.PROCESSING to setOf(PaymentStatus.AUTHORIZED, PaymentStatus.FAILED),
    PaymentStatus.AUTHORIZED to setOf(PaymentStatus.CAPTURED, PaymentStatus.CANCELED),
    PaymentStatus.AWAITING_PAYMENT to setOf(PaymentStatus.SUCCEEDED, PaymentStatus.EXPIRED, PaymentStatus.CANCELED),
    PaymentStatus.CAPTURED to setOf(PaymentStatus.REFUNDED),
    PaymentStatus.SUCCEEDED to setOf(PaymentStatus.REFUNDED),
    PaymentStatus.FAILED to emptySet(),
    PaymentStatus.CANCELED to emptySet(),
    PaymentStatus.EXPIRED to emptySet(),
    PaymentStatus.REFUNDED to emptySet(),
)

/** Objeto central. Inmutable. Montos en unidades menores (centavos). */
data class PaymentIntent(
    val id: String,
    val merchantId: String,
    val method: PaymentMethod,
    val amount: Long,
    val currency: String,
    val status: PaymentStatus,
    val providerRef: String? = null,
    val qrPayload: String? = null,
    val expiresAt: Instant? = null,
    val failureReason: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    fun transitionTo(next: PaymentStatus, at: Instant): PaymentIntent {
        if (!status.canTransitionTo(next)) throw InvalidTransitionException(id, status, next)
        return copy(status = next, updatedAt = at)
    }
}

/** Registro de auditoría: cada cambio de estado deja una huella. */
data class PaymentEvent(
    val intentId: String,
    val from: PaymentStatus?,
    val to: PaymentStatus,
    val at: Instant,
    val note: String? = null,
)

class InvalidTransitionException(id: String, from: PaymentStatus, to: PaymentStatus) :
    RuntimeException("Payment $id: cannot go from $from to $to")

class PaymentNotFoundException(id: String) : RuntimeException("Payment $id not found")

class IdempotencyConflictException :
    RuntimeException("Idempotency-Key was already used with a different request body")
