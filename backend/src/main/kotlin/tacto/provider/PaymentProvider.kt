package tacto.provider

import tacto.domain.PaymentIntent

/** Datos de pago. Nunca un PAN en claro: en hito 1 es un token de prueba. */
data class CardPayload(val token: String)

sealed interface ProviderResult {
    data class Approved(val providerRef: String) : ProviderResult
    data class Declined(val reason: String) : ProviderResult
    data class Error(val message: String) : ProviderResult
}

/** Tu dominio solo habla este idioma; nunca "Stripe" ni "Adyen". */
interface PaymentProvider {
    suspend fun authorize(intent: PaymentIntent, payload: CardPayload): ProviderResult
    suspend fun capture(providerRef: String): ProviderResult
    suspend fun void(providerRef: String): ProviderResult
    suspend fun refund(providerRef: String, amount: Long): ProviderResult
}
