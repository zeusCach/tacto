package tacto.provider

import tacto.domain.PaymentIntent
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Simula un proveedor QR. Los webhooks vienen firmados con HMAC-SHA256 (hex) del cuerpo crudo. */
class FakeQrProvider(
    private val secret: String = System.getenv("WEBHOOK_SECRET") ?: "whsec_test",
    private val clock: Clock = Clock.systemUTC(),
    private val ttl: Duration = Duration.ofMinutes(10),
) : QrProvider {

    override suspend fun createCharge(intent: PaymentIntent): ChargeInstructions {
        val ref = "qr_${UUID.randomUUID()}"
        return ChargeInstructions(
            providerRef = ref,
            qrPayload = "tacto-fake-qr://pay/$ref?amount=${intent.amount}&currency=${intent.currency}",
            expiresAt = clock.instant().plus(ttl),
        )
    }

    override suspend fun refund(providerRef: String, amount: Long): ProviderResult =
        ProviderResult.Approved(providerRef)

    override fun verifyWebhook(rawBody: String, signature: String?): Boolean {
        if (signature == null) return false
        // Comparación en tiempo constante: evita ataques por temporización.
        return MessageDigest.isEqual(sign(rawBody).toByteArray(), signature.lowercase().toByteArray())
    }

    fun sign(rawBody: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(rawBody.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
