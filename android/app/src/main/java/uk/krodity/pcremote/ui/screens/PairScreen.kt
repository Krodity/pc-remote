package uk.krodity.pcremote.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import uk.krodity.pcremote.ui.FieldStyle
import uk.krodity.pcremote.ui.RemoteViewModel
import uk.krodity.pcremote.ui.tapTarget
import uk.krodity.pcremote.ui.theme.Mono
import uk.krodity.pcremote.ui.theme.P

/**
 * First run: point the app at an agent.
 *
 * Normally nobody types anything here -- opening the agent's /pair page on the
 * phone fires a `pcremote://pair` link that fills both fields in. The manual
 * form is the fallback for when the browser is on a different device.
 */
@Composable
fun PairScreen(vm: RemoteViewModel) {
    var host by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        Modifier
            .fillMaxSize()
            .background(P.bg)
            .verticalScroll(rememberScrollState())
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Spacer(Modifier.height(48.dp))
        Box(
            Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Brush.linearGradient(listOf(P.accentD, P.purple))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Monitor, null, tint = Color.White, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(18.dp))
        Text("PC Remote", color = P.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            "Open http://<host>:8778/pair in this phone's browser and tap " +
                "“Pair this phone” — or fill these in by hand.",
            color = P.sub, fontSize = 13.sp, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))

        Text("HOST", color = P.mute, fontSize = 10.sp, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        FieldStyle(
            value = host,
            onValueChange = { host = it; error = null },
            modifier = Modifier.fillMaxWidth(),
            placeholder = "aepc  ·  100.x.y.z  ·  host:8778",
            mono = true,
        )
        Spacer(Modifier.height(16.dp))

        Text("TOKEN", color = P.mute, fontSize = 10.sp, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        FieldStyle(
            value = token,
            onValueChange = { token = it; error = null },
            modifier = Modifier.fillMaxWidth(),
            placeholder = "from ~/.config/pc-remote/token",
            mono = true,
        )

        error?.let {
            Spacer(Modifier.height(14.dp))
            Text(it, color = P.red, fontSize = 12.sp, fontFamily = Mono,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }

        Spacer(Modifier.height(24.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(if (busy) P.mute else P.accent)
                .tapTarget(enabled = !busy) {
                    busy = true
                    error = null
                    scope.launch {
                        vm.tryPair(host, token)
                            .onFailure { error = it.message ?: "could not reach the agent" }
                        busy = false
                    }
                }
                .padding(vertical = 15.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (busy) {
                CircularProgressIndicator(
                    color = P.accent, strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp),
                )
            } else {
                Text("Connect", color = Color.Black, fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}
