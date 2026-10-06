package tacto.provider

import tacto.domain.PaymentIntent
import java.time.Instant

/** Lo que el SDK necesita para mostrar el cobro al cliente. */
data class ChargeInstructions(val providerRef: String, val qrPayload: String, val expiresAt: Instant)

/**
 * Proveedor de cobros por QR (CoDi/SPEI vía un socio financiero, Mercado Pago, etc.).
 * A diferencia de la tarjeta, aquí NO hay authorize/capture: el cliente paga y el proveedor
 * te avisa de forma asíncrona por webhook.
 */
interface QrProvider {
    suspend fun createCharge(intent: PaymentIntent): ChargeInstructions
    suspend fun refund(providerRef: String, amount: Long): ProviderResult

    /** Cada proveedor firma sus webhooks a su manera; la verificación vive con él. */
    fun verifyWebhook(rawBody: String, signature: String?): Boolean
}
