package hu.ando.talkie.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import hu.ando.talkie.net.CallState
import hu.ando.talkie.net.LinkState

@Composable
fun MainNav(pendingPeer: String?, onPeerHandled: () -> Unit) {
    val nav = rememberNavController()
    val link = app().link
    val state by link.state.collectAsState()
    val call by link.call.collectAsState()

    LaunchedEffect(pendingPeer) {
        if (pendingPeer != null) { nav.navigate("chat/$pendingPeer") { launchSingleTop = true }; onPeerHandled() }
    }

    Box(Modifier.fillMaxSize()) {
        NavHost(nav, startDestination = "home") {
            composable("home") {
                HomeScreen(
                    onOpenChat = { fp -> nav.navigate("chat/$fp") },
                    onWizard = { nav.navigate("wizard") },
                )
            }
            composable("chat/{fp}") { e ->
                ChatScreen(fp = e.arguments?.getString("fp") ?: "", onBack = { nav.popBackStack() })
            }
            composable("wizard") { WizardScreen(onClose = { nav.popBackStack() }) }
        }
        if (state is LinkState.Verifying) VerifyDialog(state as LinkState.Verifying)
        AnimatedVisibility(call !is CallState.None) { CallOverlay(call) }
    }
}
