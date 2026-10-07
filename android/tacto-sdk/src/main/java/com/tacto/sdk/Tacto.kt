// Habilita el uso de APIs experimentales relacionadas con el tiempo.
@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.tacto.sdk

// Importa las herramientas necesarias para simular operaciones asíncronas.
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

// API pública del SDK.
// Es la única parte que debería utilizar directamente el comercio.
object Tacto {
    // Guarda una instancia del cliente encargado de procesar los pagos.
    private var client: TactoClient? = null

    // Inicializa el SDK con la configuración proporcionada.
    fun initialize(config: TactoConfig) {
        // Por ahora utiliza un cliente falso para simular los pagos.
        client = FakeTactoClient()
    }

    // Crea un nuevo pago de forma asíncrona.
    suspend fun createPayment(amount: Long, currency: String = "MXM"): Payment =
        // Envía la solicitud al cliente configurado.
        requireClient().createPayment(amount, currency)

    // Permite observar los cambios de estado de un pago.
    fun observePayment(id: String): Flow<Payment> =
        // Obtiene el flujo de estados desde el cliente.
        requireClient().observePayment(id)

    // Obtiene el cliente configurado.
    // Si el SDK no ha sido inicializado, muestra un error.
    private fun requireClient() =
        client ?: error("Tacto.initialize() must be called first")
}

// Define las operaciones que cualquier cliente de Tacto debe implementar.
internal interface TactoClient {
    // Crea un nuevo pago.
    suspend fun createPayment(amount: Long, currency: String): Payment

    // Observa los cambios de estado de un pago.
    fun observePayment(id: String): Flow<Payment>
}

// Implementación temporal que simula el funcionamiento del backend.
internal class FakeTactoClient : TactoClient {
    // Guarda los pagos creados en memoria.
    private val payments = mutableMapOf<String, Payment>()

    // Crea y registra un pago simulado.
    override suspend fun createPayment(amount: Long, currency: String): Payment {
        // Simula el tiempo de respuesta de una red.
        delay(500)

        // Genera un ID único para el pago.
        val id = "pi_${UUID.randomUUID()}"

        // Construye el objeto del pago.
        val payment = Payment(
            id = id,
            amount = amount,
            currency = currency,
            status = PaymentStatus.AWAITING_PAYMENT,
            qrPayload = "tacto-fake-qr://pay/$id?amount=$amount",
            expiresAt = Clock.System.now().plus(duration = 600.seconds),
        )

        // Guarda el pago en memoria.
        payments[id] = payment

        // Devuelve el pago creado.
        return payment
    }

    // Observa el estado de un pago y simula su actualización.
    override fun observePayment(id: String): Flow<Payment> = flow {
        // Obtiene el pago almacenado.
        val current = payments.getValue(id)

        // Emite el estado inicial del pago.
        emit(current)

        // Simula el tiempo que tarda en completarse el pago.
        delay(5_000)

        // Emite el pago actualizado como exitoso.
        emit(current.copy(status = PaymentStatus.SUCCEEDED))
    }
}