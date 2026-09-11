package uk.krodity.pcremote

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import uk.krodity.pcremote.data.Link
import uk.krodity.pcremote.ui.RemoteViewModel
import uk.krodity.pcremote.ui.screens.FilesScreen
import uk.krodity.pcremote.ui.screens.KeysScreen
import uk.krodity.pcremote.ui.screens.MouseScreen
import uk.krodity.pcremote.ui.screens.PairScreen
import uk.krodity.pcremote.ui.screens.ShellScreen
import uk.krodity.pcremote.ui.theme.P
import uk.krodity.pcremote.ui.theme.PcRemoteTheme
import uk.krodity.pcremote.ui.tapTarget

class MainActivity : ComponentActivity() {

    private val vm: RemoteViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handlePairLink(intent)
        setContent {
            PcRemoteTheme {
                Root(vm)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePairLink(intent)
    }

    /** pcremote://pair?host=…&token=… from the agent's pair page. */
    private fun handlePairLink(intent: Intent?) {
        val uri: Uri = intent?.data ?: return
        if (uri.scheme != "pcremote" || uri.host != "pair") return
        val host = uri.getQueryParameter("host") ?: return
        val token = uri.getQueryParameter("token") ?: return
        // Pairing writes to DataStore, so it has to happen in a coroutine.
        lifecycleScope.launch { vm.tryPair(host, token) }
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    FILES("Files", Icons.Filled.Storage),
    MOUSE("Mouse", Icons.Filled.Mouse),
    KEYS("Keys", Icons.Filled.Keyboard),
    SHELL("Shell", Icons.Filled.Terminal),
}

@Composable
private fun Root(vm: RemoteViewModel) {
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(vm.toast) {
        vm.toast?.let {
            snackbar.showSnackbar(it)
            vm.toast = null
        }
    }

    if (!vm.pairing.isSet) {
        PairScreen(vm)
        return
    }

    var tab by rememberSaveable { mutableStateOf(Tab.MOUSE) }

    Scaffold(
        containerColor = P.bg,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { TopBar(vm) },
        bottomBar = { BottomNav(tab) { tab = it } },
    ) { pad ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .background(P.bg)
        ) {
            when (tab) {
                Tab.FILES -> FilesScreen(vm)
                Tab.MOUSE -> MouseScreen(vm)
                Tab.KEYS -> KeysScreen(vm)
                Tab.SHELL -> ShellScreen(vm)
            }
        }
    }
}

@Composable
private fun TopBar(vm: RemoteViewModel) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(P.nav)
            .statusBarsPadding()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(50.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Brush.linearGradient(listOf(P.accentD, P.purple))),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Monitor, null, tint = Color.White,
                    modifier = Modifier.size(15.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text(
                vm.info?.host?.uppercase() ?: vm.pairing.host.uppercase(),
                color = P.text, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.weight(1f))

            val (dot, label) = when (vm.link) {
                Link.ONLINE -> P.green to (vm.pingMs?.let { "${it}ms" } ?: "up")
                Link.CONNECTING -> P.amber to "…"
                Link.OFFLINE -> P.red to "offline"
            }
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(dot)
            )
            Spacer(Modifier.width(6.dp))
            Text(label, color = dot, fontSize = 12.sp)
            Spacer(Modifier.width(10.dp))

            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .tapTarget { vm.connect() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Refresh, "Reconnect", tint = P.sub,
                    modifier = Modifier.size(15.dp))
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(P.border))
    }
}

@Composable
private fun BottomNav(current: Tab, onSelect: (Tab) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(P.nav)
            .imePadding()
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(P.border))
        Row(
            Modifier
                .fillMaxWidth()
                .height(62.dp)
                .navigationBarsPadding()
        ) {
            Tab.entries.forEach { t ->
                val active = t == current
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .tapTarget { onSelect(t) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (active) {
                        Box(
                            Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth(0.6f)
                                .height(2.dp)
                                .clip(RoundedCornerShape(bottomStart = 3.dp, bottomEnd = 3.dp))
                                .background(P.accent)
                        )
                    }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            t.icon, t.label,
                            tint = if (active) P.accent else P.sub,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            t.label,
                            fontSize = 10.sp,
                            color = if (active) P.accent else P.sub,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}
