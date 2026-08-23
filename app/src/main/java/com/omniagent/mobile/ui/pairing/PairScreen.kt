package com.omniagent.mobile.ui.pairing

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.omniagent.mobile.app.PairViewModel
import com.omniagent.mobile.qr.QrCodec
import com.omniagent.mobile.ui.theme.LocalOmniColors

@Composable
fun PairScreen(
    onPaired: () -> Unit,
    viewModel: PairViewModel = viewModel(),
) {
    val colors = LocalOmniColors.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showScanner by remember { mutableStateOf(false) }
    var demoQr by remember { mutableStateOf(false) }

    LaunchedEffect(state.connected) {
        if (state.connected) {
            kotlinx.coroutines.delay(450)
            onPaired()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(72.dp))
        Text("OmniAgent", style = MaterialTheme.typography.headlineSmall, color = colors.text)
        Spacer(Modifier.height(6.dp))
        Text(
            "Guide your agent sessions from anywhere.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textDim,
        )
        Spacer(Modifier.height(36.dp))

        Button(
            onClick = { showScanner = true },
            enabled = !state.connecting,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.accent,
                contentColor = colors.sendContent,
            ),
        ) {
            if (state.connecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = colors.sendContent,
                )
            } else {
                Text("Scan pairing code")
            }
        }

        TextButton(
            onClick = { demoQr = !demoQr },
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Text("Show sample code", color = colors.textDim)
        }

        if (demoQr) {
            val bitmap = remember(demoQr) {
                QrCodec.encode(
                    "http://192.168.1.50:4096",
                    560,
                    android.graphics.Color.argb(255, 43, 33, 25),
                    android.graphics.Color.WHITE,
                )
            }
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Sample QR",
                modifier = Modifier.size(180.dp).clip(RoundedCornerShape(12.dp)),
            )
        }

        Spacer(Modifier.height(28.dp))
        Text("— or enter manually —", style = MaterialTheme.typography.labelMedium, color = colors.textFaint)
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = state.manualHost,
            onValueChange = { viewModel.updateManual(it, state.manualPort, state.manualPassword) },
            label = { Text("Host (e.g. 192.168.1.20)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(9.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = colors.accent,
                unfocusedBorderColor = colors.borderStrong,
                cursorColor = colors.accent,
                focusedLabelColor = colors.accent,
            ),
        )
        Spacer(Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = state.manualPort,
                onValueChange = { viewModel.updateManual(state.manualHost, it, state.manualPassword) },
                label = { Text("Port") },
                singleLine = true,
                modifier = Modifier.weight(0.38f),
                shape = RoundedCornerShape(9.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.accent,
                    unfocusedBorderColor = colors.borderStrong,
                    cursorColor = colors.accent,
                    focusedLabelColor = colors.accent,
                ),
            )
            OutlinedTextField(
                value = state.manualPassword,
                onValueChange = { viewModel.updateManual(state.manualHost, state.manualPort, it) },
                label = { Text("Pairing password") },
                singleLine = true,
                modifier = Modifier.weight(0.62f),
                shape = RoundedCornerShape(9.dp),
                visualTransformation = PasswordVisualTransformation(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.accent,
                    unfocusedBorderColor = colors.borderStrong,
                    cursorColor = colors.accent,
                    focusedLabelColor = colors.accent,
                ),
            )
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { viewModel.connectManual() },
            enabled = !state.connecting,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.bgActivePill,
                contentColor = colors.text,
            ),
        ) { Text("Connect") }

        state.statusMessage?.let { msg ->
            Spacer(Modifier.height(14.dp))
            Text(
                msg,
                style = MaterialTheme.typography.bodySmall,
                color = if (state.connected) colors.green else colors.red,
            )
        }

        if (state.saved != null && !state.connected) {
            Spacer(Modifier.height(22.dp))
            TextButton(onClick = { viewModel.forgetPairing() }) {
                Text("Forget this Mac", color = colors.textDim)
            }
        }
        Spacer(Modifier.height(40.dp))
    }

    if (showScanner) {
        CameraScanDialog(
            onDismiss = { showScanner = false },
            onResult = { raw ->
                showScanner = false
                viewModel.connectScanned(raw)
            },
        )
    }
}
