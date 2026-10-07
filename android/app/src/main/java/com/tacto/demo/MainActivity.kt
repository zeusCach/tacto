package com.tacto.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tacto.sdk.Payment
import com.tacto.sdk.PaymentStatus
import com.tacto.sdk.Tacto
import com.tacto.sdk.TactoConfig
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.launch

@OptIn(ExperimentalTime::class)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Tacto.initialize(TactoConfig(baseUrl = "http://10.0.2.2:8080", merchantId = "m1"))
        setContent { MaterialTheme { PaymentScreen() } }
    }
}

@OptIn(ExperimentalTime::class)
@Composable
fun PaymentScreen() {
    val scope = rememberCoroutineScope()
    var payment by remember { mutableStateOf<Payment?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val waiting = payment?.status == PaymentStatus.AWAITING_PAYMENT

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Button(
            enabled = !waiting,
            onClick = {
                error = null
                scope.launch {
                    try {
                        val created = Tacto.createPayment(amount = 15000)
                        payment = created
                        Tacto.observePayment(created.id).collect { payment = it }
                    } catch (e: Exception) {
                        error = e.message
                    }
                }
            },
        ) { Text("Cobrar \$150.00") }

        payment?.let {
            Text("Estado: ${it.status}")
            Text("QR: ${it.qrPayload}")
        }
        error?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error) }
    }
}