package com.omniagent.mobile

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.omniagent.mobile.app.PairViewModel
import com.omniagent.mobile.data.PairingStore
import com.omniagent.mobile.ui.chat.ChatScreen
import com.omniagent.mobile.ui.pairing.PairScreen
import com.omniagent.mobile.ui.sessions.SessionsScreen
import com.omniagent.mobile.ui.theme.OmniAgentTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OmniAgentTheme {
                AppRoot(intent = intent)
            }
        }
    }
}

@Composable
fun AppRoot(intent: Intent?) {
    val navController = rememberNavController()
    val pairViewModel: PairViewModel = viewModel()
    val pairState by pairViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val store = remember { PairingStore(context) }

    var deepLinkPairing by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(intent) {
        intent?.data?.let { uri ->
            if (uri.scheme == "omniagent" && uri.host == "pair") {
                uri.getQueryParameter("url")?.let { deepLinkPairing = it }
            }
        }
    }

    LaunchedEffect(deepLinkPairing) {
        deepLinkPairing?.let { raw -> pairViewModel.connectScanned(raw) }
    }

    val startDestination = if (pairState.saved != null) "sessions" else "pair"

    NavHost(navController = navController, startDestination = startDestination) {
        composable("pair") {
            PairScreen(onPaired = { navController.navigate("sessions") { popUpTo("pair") { inclusive = true } } })
        }
        composable("sessions") {
            SessionsScreen(
                onOpenSession = { session ->
                    navController.navigate("chat/${session.id}")
                },
            )
        }
        composable("chat/{sessionId}") { entry ->
            val pairing = pairState.saved ?: store.load() ?: return@composable
            val sessionId = entry.arguments?.getString("sessionId") ?: return@composable
            ChatScreen(
                sessionId = sessionId,
                target = pairing.target,
                password = pairing.target.password,
                onBack = { navController.popBackStack() },
            )
        }
    }

    LaunchedEffect(pairState.saved) {
        if (startDestination == "pair" && pairState.saved != null && navController.currentDestination?.route == "pair") {
            navController.navigate("sessions") { popUpTo("pair") { inclusive = true } }
        }
    }
}
