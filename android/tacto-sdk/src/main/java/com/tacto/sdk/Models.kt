package com.tacto.sdk

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

data class TactoConfig(
    val baseUrl: String,
    val merchantId: String,
)

enum class PaymentStatus { AWAITING_PAYMENT, SUCCEEDED, EXPIRED, CANCELED, FAILED }

@ExperimentalTime
data class Payment(
    val id: String,
    val amount: Long,
    val currency: String,
    val status: PaymentStatus,
    val qrPayload: String?,
    val expiresAt: Instant?,
)