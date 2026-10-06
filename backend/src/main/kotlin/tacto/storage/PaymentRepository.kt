package tacto.storage

import tacto.domain.PaymentEvent
import tacto.domain.PaymentIntent
import tacto.domain.PaymentStatus
import java.util.concurrent.ConcurrentHashMap

data class IdempotencyRecord(val fingerprint: String, val intentId: String)

interface PaymentRepository {
    suspend fun save(intent: PaymentIntent)
    suspend fun find(id: String): PaymentIntent?
    suspend fun findByProviderRef(providerRef: String): PaymentIntent?
    suspend fun findByStatus(status: PaymentStatus): List<PaymentIntent>
    suspend fun appendEvent(event: PaymentEvent)
    suspend fun events(intentId: String): List<PaymentEvent>
    suspend fun findIdempotency(merchantId: String, key: String): IdempotencyRecord?
    suspend fun saveIdempotency(merchantId: String, key: String, record: IdempotencyRecord)

    /** Webhooks ya procesados (el proveedor puede enviar el mismo evento varias veces). */
    suspend fun isEventProcessed(eventId: String): Boolean
    suspend fun markEventProcessed(eventId: String)
}

/** Para aprender y probar. El siguiente paso: PostgresPaymentRepository con la misma interfaz. */
class InMemoryPaymentRepository : PaymentRepository {
    private val intents = ConcurrentHashMap<String, PaymentIntent>()
    private val events = ConcurrentHashMap<String, MutableList<PaymentEvent>>()
    private val keys = ConcurrentHashMap<String, IdempotencyRecord>()
    private val processed = ConcurrentHashMap.newKeySet<String>()

    override suspend fun save(intent: PaymentIntent) { intents[intent.id] = intent }
    override suspend fun find(id: String) = intents[id]
    override suspend fun findByProviderRef(providerRef: String) =
        intents.values.firstOrNull { it.providerRef == providerRef }
    override suspend fun findByStatus(status: PaymentStatus) = intents.values.filter { it.status == status }

    override suspend fun appendEvent(event: PaymentEvent) {
        events.computeIfAbsent(event.intentId) { java.util.Collections.synchronizedList(mutableListOf()) }.add(event)
    }

    override suspend fun events(intentId: String) = events[intentId]?.toList() ?: emptyList()
    override suspend fun findIdempotency(merchantId: String, key: String) = keys["$merchantId:$key"]
    override suspend fun saveIdempotency(merchantId: String, key: String, record: IdempotencyRecord) {
        keys["$merchantId:$key"] = record
    }

    override suspend fun isEventProcessed(eventId: String) = eventId in processed
    override suspend fun markEventProcessed(eventId: String) { processed.add(eventId) }
}
