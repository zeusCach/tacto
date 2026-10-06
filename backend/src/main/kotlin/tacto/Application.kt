package tacto

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import tacto.domain.*
import tacto.provider.FakeProvider
import tacto.provider.FakeQrProvider
import tacto.service.PaymentService
import tacto.storage.InMemoryPaymentRepository

@Serializable data class CreateIntentRequest(val amount: Long, val currency: String, val method: String = "qr")
@Serializable data class AuthorizeRequest(val cardToken: String)
@Serializable data class ErrorBody(val error: String, val message: String)
@Serializable data class PaymentIntentResponse(
    val id: String, val merchantId: String, val method: String, val amount: Long, val currency: String,
    val status: String, val providerRef: String? = null, val qrPayload: String? = null,
    val expiresAt: String? = null, val failureReason: String? = null,
    val createdAt: String, val updatedAt: String,
)
@Serializable data class EventResponse(val from: String?, val to: String, val at: String, val note: String? = null)

/** Cuerpo del webhook (formato del proveedor simulado). */
@Serializable data class QrWebhookEvent(val eventId: String, val type: String, val providerRef: String, val amount: Long)
@Serializable data class WebhookAck(val received: Boolean, val duplicate: Boolean)

private val webhookJson = Json { ignoreUnknownKeys = true }

private fun PaymentIntent.toResponse() = PaymentIntentResponse(
    id, merchantId, method.name.lowercase(), amount, currency, status.name.lowercase(), providerRef,
    qrPayload, expiresAt?.toString(), failureReason, createdAt.toString(), updatedAt.toString(),
)

private fun PaymentEvent.toResponse() = EventResponse(from?.name?.lowercase(), to.name.lowercase(), at.toString(), note)

fun main() {
    embeddedServer(Netty, port = 8080) { module() }.start(wait = true)
}

fun defaultService() = PaymentService(InMemoryPaymentRepository(), FakeProvider(), FakeQrProvider())

fun Application.module(
    service: PaymentService = defaultService(),
    expirationIntervalMs: Long = 30_000,
) {
    install(ContentNegotiation) { json() }

    install(StatusPages) {
        exception<InvalidTransitionException> { call, e ->
            call.respond(HttpStatusCode.Conflict, ErrorBody("invalid_transition", e.message ?: ""))
        }
        exception<PaymentNotFoundException> { call, e ->
            call.respond(HttpStatusCode.NotFound, ErrorBody("not_found", e.message ?: ""))
        }
        exception<IdempotencyConflictException> { call, e ->
            call.respond(HttpStatusCode.UnprocessableEntity, ErrorBody("idempotency_conflict", e.message ?: ""))
        }
        exception<IllegalArgumentException> { call, e ->
            call.respond(HttpStatusCode.BadRequest, ErrorBody("bad_request", e.message ?: ""))
        }
        exception<SerializationException> { call, e ->
            call.respond(HttpStatusCode.BadRequest, ErrorBody("bad_json", e.message ?: ""))
        }
        exception<IllegalStateException> { call, e ->
            call.respond(HttpStatusCode.BadGateway, ErrorBody("provider_error", e.message ?: ""))
        }
    }

    // Job: vence los QR que nadie pagó a tiempo.
    launch {
        while (isActive) {
            delay(expirationIntervalMs)
            runCatching { service.expireOverdue() }
                .onSuccess { if (it > 0) environment.log.info("Expired $it QR payments") }
                .onFailure { environment.log.error("expireOverdue failed", it) }
        }
    }

    routing {
        route("/v1/payment_intents") {
            post {
                val merchant = call.merchantId()
                val key = call.request.headers["Idempotency-Key"]
                    ?: throw IllegalArgumentException("Idempotency-Key header is required")
                val body = call.receive<CreateIntentRequest>()
                val method = PaymentMethod.valueOf(body.method.uppercase())
                val intent = service.create(merchant, key, body.amount, body.currency, method)
                call.respond(HttpStatusCode.Created, intent.toResponse())
            }
            route("/{id}") {
                get { call.respond(service.get(call.intentId(), call.merchantId()).toResponse()) }
                get("/events") {
                    call.respond(service.events(call.intentId(), call.merchantId()).map { it.toResponse() })
                }
                post("/authorize") { // solo tarjeta
                    val body = call.receive<AuthorizeRequest>()
                    call.respond(service.authorize(call.intentId(), call.merchantId(), body.cardToken).toResponse())
                }
                post("/capture") { call.respond(service.capture(call.intentId(), call.merchantId()).toResponse()) } // solo tarjeta
                post("/cancel") { call.respond(service.cancel(call.intentId(), call.merchantId()).toResponse()) }
                post("/refund") { call.respond(service.refund(call.intentId(), call.merchantId()).toResponse()) }
            }
        }

        // Webhook entrante: lo llama el PROVEEDOR, no el comercio. Se autentica con la firma, no con X-Merchant-Id.
        post("/webhooks/qr") {
            val raw = call.receiveText() // firma sobre el cuerpo CRUDO, antes de parsear
            if (!service.verifyQrWebhook(raw, call.request.headers["X-Tacto-Signature"])) {
                call.respond(HttpStatusCode.Unauthorized, ErrorBody("invalid_signature", "signature mismatch"))
                return@post
            }
            val event = webhookJson.decodeFromString<QrWebhookEvent>(raw)
            if (event.type != "payment.succeeded") {
                call.respond(WebhookAck(received = true, duplicate = false)) // eventos que aún no manejamos
                return@post
            }
            val result = service.handleQrPaymentSucceeded(event.eventId, event.providerRef, event.amount)
            call.respond(WebhookAck(received = true, duplicate = result == null))
        }
    }
}

// Provisional: en el hito 6 esto vendrá de una API key / token de terminal validado.
private fun ApplicationCall.merchantId(): String =
    request.headers["X-Merchant-Id"] ?: throw IllegalArgumentException("X-Merchant-Id header is required")

private fun ApplicationCall.intentId(): String = parameters["id"]!!
