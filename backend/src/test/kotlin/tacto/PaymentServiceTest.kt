package tacto

import kotlinx.coroutines.test.runTest
import tacto.domain.*
import tacto.provider.FakeProvider
import tacto.provider.FakeQrProvider
import tacto.service.PaymentService
import tacto.storage.InMemoryPaymentRepository
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.*

class MutableClock(var current: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = current
}

class PaymentServiceTest {
    private val clock = MutableClock(Instant.parse("2026-10-05T12:00:00Z"))
    private val qr = FakeQrProvider(secret = "test_secret", clock = clock, ttl = Duration.ofMinutes(10))
    private val service = PaymentService(InMemoryPaymentRepository(), FakeProvider(), qr, clock)
    private val m = "merchant_1"

    private suspend fun card(key: String, amount: Long = 1000) =
        service.create(m, key, amount, "MXN", PaymentMethod.CARD)

    private suspend fun qrPayment(key: String, amount: Long = 15000) =
        service.create(m, key, amount, "MXN", PaymentMethod.QR)

    // ------------------------------------------------------------ tarjeta

    @Test fun `card happy path create authorize capture refund`() = runTest {
        val pi = card("c1", 15000)
        assertEquals(PaymentStatus.CREATED, pi.status)
        assertEquals(PaymentStatus.AUTHORIZED, service.authorize(pi.id, m, "tok_approved").status)
        assertEquals(PaymentStatus.CAPTURED, service.capture(pi.id, m).status)
        assertEquals(PaymentStatus.REFUNDED, service.refund(pi.id, m).status)
        assertEquals(
            listOf("created", "processing", "authorized", "captured", "refunded"),
            service.events(pi.id, m).map { it.to.name.lowercase() },
        )
    }

    @Test fun `declined card ends in FAILED with reason`() = runTest {
        val pi = card("c2")
        val r = service.authorize(pi.id, m, "tok_declined")
        assertEquals(PaymentStatus.FAILED, r.status)
        assertEquals("insufficient_funds", r.failureReason)
    }

    @Test fun `cannot capture a payment that is not authorized`() = runTest {
        val pi = card("c3")
        assertFailsWith<InvalidTransitionException> { service.capture(pi.id, m) }
    }

    @Test fun `cannot move out of a terminal state`() = runTest {
        val pi = card("c4")
        service.authorize(pi.id, m, "tok_declined")
        assertFailsWith<InvalidTransitionException> { service.authorize(pi.id, m, "tok_approved") }
    }

    @Test fun `authorize only applies to card payments`() = runTest {
        val pi = qrPayment("c5")
        assertFailsWith<IllegalArgumentException> { service.authorize(pi.id, m, "tok_approved") }
    }

    // ------------------------------------------------------------ idempotencia y aislamiento

    @Test fun `same idempotency key returns the same intent`() = runTest {
        val a = qrPayment("dup", 5000)
        val b = qrPayment("dup", 5000)
        assertEquals(a.id, b.id)
        assertEquals(a.qrPayload, b.qrPayload) // no se generó un segundo QR
    }

    @Test fun `same idempotency key with different body is rejected`() = runTest {
        qrPayment("dup2", 5000)
        assertFailsWith<IdempotencyConflictException> { qrPayment("dup2", 9999) }
    }

    @Test fun `a merchant cannot read another merchant's payment`() = runTest {
        val pi = qrPayment("iso")
        assertFailsWith<PaymentNotFoundException> { service.get(pi.id, "merchant_2") }
    }

    // ------------------------------------------------------------ QR

    @Test fun `creating a QR payment returns a QR and waits for payment`() = runTest {
        val pi = qrPayment("q1")
        assertEquals(PaymentStatus.AWAITING_PAYMENT, pi.status)
        assertNotNull(pi.qrPayload)
        assertNotNull(pi.providerRef)
        assertEquals(clock.current.plus(Duration.ofMinutes(10)), pi.expiresAt)
    }

    @Test fun `webhook confirms the payment`() = runTest {
        val pi = qrPayment("q2")
        val paid = service.handleQrPaymentSucceeded("evt_1", pi.providerRef!!, 15000)
        assertEquals(PaymentStatus.SUCCEEDED, paid?.status)
        assertEquals(
            listOf("created", "awaiting_payment", "succeeded"),
            service.events(pi.id, m).map { it.to.name.lowercase() },
        )
    }

    @Test fun `duplicate webhook event is ignored`() = runTest {
        val pi = qrPayment("q3")
        assertNotNull(service.handleQrPaymentSucceeded("evt_dup", pi.providerRef!!, 15000))
        assertNull(service.handleQrPaymentSucceeded("evt_dup", pi.providerRef!!, 15000))
        assertEquals(3, service.events(pi.id, m).size) // no se duplicó ningún cambio de estado
    }

    @Test fun `same payment notified with a different event id is still idempotent`() = runTest {
        val pi = qrPayment("q4")
        service.handleQrPaymentSucceeded("evt_a", pi.providerRef!!, 15000)
        val again = service.handleQrPaymentSucceeded("evt_b", pi.providerRef!!, 15000)
        assertEquals(PaymentStatus.SUCCEEDED, again?.status)
        assertEquals(3, service.events(pi.id, m).size)
    }

    @Test fun `webhook with a wrong amount is rejected`() = runTest {
        val pi = qrPayment("q5")
        assertFailsWith<IllegalArgumentException> { service.handleQrPaymentSucceeded("evt_x", pi.providerRef!!, 1) }
        assertEquals(PaymentStatus.AWAITING_PAYMENT, service.get(pi.id, m).status)
    }

    @Test fun `unpaid QR expires after its deadline`() = runTest {
        val pi = qrPayment("q6")
        clock.current = clock.current.plus(Duration.ofMinutes(11))
        assertEquals(1, service.expireOverdue())
        assertEquals(PaymentStatus.EXPIRED, service.get(pi.id, m).status)
    }

    @Test fun `a paid QR is not expired`() = runTest {
        val pi = qrPayment("q7")
        service.handleQrPaymentSucceeded("evt_p", pi.providerRef!!, 15000)
        clock.current = clock.current.plus(Duration.ofMinutes(11))
        assertEquals(0, service.expireOverdue())
        assertEquals(PaymentStatus.SUCCEEDED, service.get(pi.id, m).status)
    }

    @Test fun `a payment arriving after expiry is rejected - late payment case`() = runTest {
        val pi = qrPayment("q8")
        clock.current = clock.current.plus(Duration.ofMinutes(11))
        service.expireOverdue()
        assertFailsWith<InvalidTransitionException> {
            service.handleQrPaymentSucceeded("evt_late", pi.providerRef!!, 15000)
        }
    }

    @Test fun `QR payment can be refunded after success`() = runTest {
        val pi = qrPayment("q9")
        service.handleQrPaymentSucceeded("evt_r", pi.providerRef!!, 15000)
        assertEquals(PaymentStatus.REFUNDED, service.refund(pi.id, m).status)
    }

    @Test fun `awaiting QR can be canceled`() = runTest {
        val pi = qrPayment("q10")
        assertEquals(PaymentStatus.CANCELED, service.cancel(pi.id, m).status)
    }

    // ------------------------------------------------------------ firma del webhook

    @Test fun `valid signature is accepted, tampered or missing is rejected`() {
        val body = """{"eventId":"e1","type":"payment.succeeded","providerRef":"qr_1","amount":100}"""
        val sig = qr.sign(body)
        assertTrue(service.verifyQrWebhook(body, sig))
        assertFalse(service.verifyQrWebhook(body.replace("100", "999999"), sig))
        assertFalse(service.verifyQrWebhook(body, "deadbeef"))
        assertFalse(service.verifyQrWebhook(body, null))
    }
}
