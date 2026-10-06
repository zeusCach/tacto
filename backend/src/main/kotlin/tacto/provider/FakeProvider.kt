package tacto.provider

import tacto.domain.PaymentIntent
import java.util.UUID

/**
 * Simula un proveedor. El comportamiento depende del token:
 *  - "tok_approved" -> aprobado
 *  - "tok_declined" -> rechazado
 *  - "tok_error"    -> falla técnica del proveedor
 */
class FakeProvider : PaymentProvider {
    override suspend fun authorize(intent: PaymentIntent, payload: CardPayload): ProviderResult =
        when (payload.token) {
            "tok_approved" -> ProviderResult.Approved("fake_${UUID.randomUUID()}")
            "tok_declined" -> ProviderResult.Declined("insufficient_funds")
            "tok_error" -> ProviderResult.Error("provider_unavailable")
            else -> ProviderResult.Declined("unknown_token")
        }

    override suspend fun capture(providerRef: String): ProviderResult = ProviderResult.Approved(providerRef)
    override suspend fun void(providerRef: String): ProviderResult = ProviderResult.Approved(providerRef)
    override suspend fun refund(providerRef: String, amount: Long): ProviderResult = ProviderResult.Approved(providerRef)
}
