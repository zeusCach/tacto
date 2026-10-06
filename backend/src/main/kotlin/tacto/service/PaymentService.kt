package tacto.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tacto.domain.*
import tacto.provider.*
import tacto.storage.*
import java.time.Clock
import java.time.Instant
import java.util.UUID

class PaymentService(
    private val repo: PaymentRepository,
    private val cardProvider: PaymentProvider,
    private val qrProvider: QrProvider,
    private val clock: Clock = Clock.systemUTC(),
) {
    // Una sola instancia del backend: un mutex basta. Con varias, se reemplaza por
    // bloqueo optimista/transacciones en Postgres.
    private val mutex = Mutex()
    private fun now(): Instant = clock.instant()

    // ---------------------------------------------------------------- crear

    suspend fun create(
        merchantId: String, idempotencyKey: String, amount: Long, currency: String,
        method: PaymentMethod = PaymentMethod.QR,
    ): PaymentIntent {
        require(amount > 0) { "amount must be > 0" }
        require(currency.length == 3) { "currency must be a 3-letter ISO code" }
        val fingerprint = "$amount:${currency.uppercase()}:$method"

        val (intent, isNew) = mutex.withLock {
            val existing = repo.findIdempotency(merchantId, idempotencyKey)
            if (existing != null) {
                if (existing.fingerprint != fingerprint) throw IdempotencyConflictException()
                return@withLock get(existing.intentId, merchantId) to false // mismo resultado, no crea otro pago
            }
            val t = now()
            val created = PaymentIntent(
                id = "pi_${UUID.randomUUID()}", merchantId = merchantId, method = method, amount = amount,
                currency = currency.uppercase(), status = PaymentStatus.CREATED, createdAt = t, updatedAt = t,
            )
            repo.save(created)
            repo.appendEvent(PaymentEvent(created.id, null, PaymentStatus.CREATED, t))
            repo.saveIdempotency(merchantId, idempotencyKey, IdempotencyRecord(fingerprint, created.id))
            created to true
        }
        // Un QR se genera apenas se crea el pago, para que el SDK tenga algo que mostrar.
        return if (isNew && method == PaymentMethod.QR) startQrCharge(intent) else intent
    }

    private suspend fun startQrCharge(intent: PaymentIntent): PaymentIntent {
        val charge = try {
            qrProvider.createCharge(intent) // llamada lenta: fuera del lock
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return move(intent.id, intent.merchantId, PaymentStatus.FAILED, note = "provider_unavailable") {
                it.copy(failureReason = "provider_unavailable")
            }
        }
        return move(intent.id, intent.merchantId, PaymentStatus.AWAITING_PAYMENT) {
            it.copy(providerRef = charge.providerRef, qrPayload = charge.qrPayload, expiresAt = charge.expiresAt)
        }
    }

    // ---------------------------------------------------------------- consultas

    suspend fun get(id: String, merchantId: String): PaymentIntent {
        val intent = repo.find(id)
        if (intent == null || intent.merchantId != merchantId) throw PaymentNotFoundException(id)
        return intent
    }

    suspend fun events(id: String, merchantId: String): List<PaymentEvent> {
        get(id, merchantId)
        return repo.events(id)
    }

    // ---------------------------------------------------------------- QR: webhook entrante

    fun verifyQrWebhook(rawBody: String, signature: String?): Boolean =
        qrProvider.verifyWebhook(rawBody, signature)

    /**
     * Procesa "el cliente pagó". Devuelve null si el evento ya se había procesado (duplicado).
     * Es seguro llamarlo varias veces con el mismo evento: el proveedor reintenta hasta recibir 200.
     */
    suspend fun handleQrPaymentSucceeded(eventId: String, providerRef: String, amount: Long): PaymentIntent? {
        if (repo.isEventProcessed(eventId)) return null

        val intent = repo.findByProviderRef(providerRef) ?: throw PaymentNotFoundException(providerRef)
        val result = when {
            // Mismo pago notificado con otro eventId: idempotente por estado.
            intent.status == PaymentStatus.SUCCEEDED -> intent
            amount != intent.amount ->
                throw IllegalArgumentException("amount mismatch: expected ${intent.amount}, got $amount")
            else -> move(intent.id, intent.merchantId, PaymentStatus.SUCCEEDED, note = "event=$eventId")
        }
        repo.markEventProcessed(eventId)
        return result
    }

    /** Lo llama un job periódico. Devuelve cuántos pagos venció. */
    suspend fun expireOverdue(): Int {
        val t = now()
        var count = 0
        for (intent in repo.findByStatus(PaymentStatus.AWAITING_PAYMENT)) {
            val expiresAt = intent.expiresAt ?: continue
            if (expiresAt.isAfter(t)) continue
            try {
                // move() relee el estado dentro del lock: si el cliente pagó justo ahora, falla y lo saltamos.
                move(intent.id, intent.merchantId, PaymentStatus.EXPIRED, note = "qr_expired")
                count++
            } catch (_: InvalidTransitionException) {
            }
        }
        return count
    }

    // ---------------------------------------------------------------- tarjeta

    suspend fun authorize(id: String, merchantId: String, cardToken: String): PaymentIntent {
        requireMethod(id, merchantId, PaymentMethod.CARD)
        val processing = move(id, merchantId, PaymentStatus.PROCESSING)
        return when (val r = cardProvider.authorize(processing, CardPayload(cardToken))) {
            is ProviderResult.Approved ->
                move(id, merchantId, PaymentStatus.AUTHORIZED) { it.copy(providerRef = r.providerRef) }
            is ProviderResult.Declined ->
                move(id, merchantId, PaymentStatus.FAILED, note = r.reason) { it.copy(failureReason = r.reason) }
            is ProviderResult.Error ->
                // En producción esto sería un estado "desconocido" a conciliar con el proveedor.
                move(id, merchantId, PaymentStatus.FAILED, note = r.message) { it.copy(failureReason = r.message) }
        }
    }

    suspend fun capture(id: String, merchantId: String): PaymentIntent {
        requireMethod(id, merchantId, PaymentMethod.CARD)
        val current = requireTransition(id, merchantId, PaymentStatus.CAPTURED)
        cardProvider.capture(current.providerRef!!).orThrow()
        return move(id, merchantId, PaymentStatus.CAPTURED)
    }

    // ---------------------------------------------------------------- comunes

    suspend fun cancel(id: String, merchantId: String): PaymentIntent {
        val current = requireTransition(id, merchantId, PaymentStatus.CANCELED)
        if (current.method == PaymentMethod.CARD) {
            current.providerRef?.let { cardProvider.void(it).orThrow() } // libera la retención
        }
        return move(id, merchantId, PaymentStatus.CANCELED)
    }

    suspend fun refund(id: String, merchantId: String): PaymentIntent {
        val current = requireTransition(id, merchantId, PaymentStatus.REFUNDED)
        val ref = current.providerRef!!
        when (current.method) {
            PaymentMethod.CARD -> cardProvider.refund(ref, current.amount)
            PaymentMethod.QR -> qrProvider.refund(ref, current.amount)
        }.orThrow()
        return move(id, merchantId, PaymentStatus.REFUNDED)
    }

    // ---------------------------------------------------------------- internos

    private fun ProviderResult.orThrow() {
        when (this) {
            is ProviderResult.Approved -> Unit
            is ProviderResult.Declined -> throw IllegalStateException("provider declined: $reason")
            is ProviderResult.Error -> throw IllegalStateException("provider error: $message")
        }
    }

    private suspend fun requireMethod(id: String, merchantId: String, method: PaymentMethod): PaymentIntent {
        val intent = get(id, merchantId)
        require(intent.method == method) { "this operation only applies to ${method.name} payments" }
        return intent
    }

    private suspend fun requireTransition(id: String, merchantId: String, to: PaymentStatus): PaymentIntent {
        val current = get(id, merchantId)
        if (!current.status.canTransitionTo(to)) throw InvalidTransitionException(id, current.status, to)
        return current
    }

    /** Único punto por el que cambia un estado: valida, guarda y deja el evento de auditoría. */
    private suspend fun move(
        id: String, merchantId: String, to: PaymentStatus, note: String? = null,
        update: (PaymentIntent) -> PaymentIntent = { it },
    ): PaymentIntent = mutex.withLock {
        val current = get(id, merchantId)
        val next = update(current.transitionTo(to, now()))
        repo.save(next)
        repo.appendEvent(PaymentEvent(id, current.status, to, next.updatedAt, note))
        next
    }
}
