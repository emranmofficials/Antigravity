package id.vern.antigravity.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.unit.dp
import id.vern.antigravity.ui.components.DialogBlurState
import id.vern.antigravity.ui.components.AppUpdateDialog
import id.vern.antigravity.util.UpdateChecker
import id.vern.antigravity.ui.screens.SplashScreen
import id.vern.antigravity.ui.screens.WorkspaceScreen
import id.vern.antigravity.ui.theme.AntigravityTheme
import id.vern.antigravity.viewmodel.AuthDelegate
import id.vern.antigravity.viewmodel.AntigravityViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppDestination {
    SPLASH,
    WORKSPACE
}

class MainActivity : ComponentActivity() {

    private val viewModel: AntigravityViewModel by viewModels()

    private val mutableDeepLinkDestination = MutableStateFlow<AppDestination?>(null)
    /** Destination requested by an incoming antigravity:// link. */
    val deepLinkDestination: StateFlow<AppDestination?> = mutableDeepLinkDestination.asStateFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.loadPreferences(this)
        handleIncomingIntent(intent)
        enableEdgeToEdge()

        setContent {
            val appearance by viewModel.appearanceConfig.collectAsStateWithLifecycle()
            val themePreset by viewModel.themePreset.collectAsStateWithLifecycle()
            val customBg by viewModel.customThemeBg.collectAsStateWithLifecycle()
            val customFg by viewModel.customThemeFg.collectAsStateWithLifecycle()
            val customAccent by viewModel.customThemeAccent.collectAsStateWithLifecycle()
            val isDark = when (appearance.themeMode) {
                id.vern.antigravity.data.ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
                id.vern.antigravity.data.ThemeMode.LIGHT -> false
                id.vern.antigravity.data.ThemeMode.DARK -> true
            }

            AntigravityTheme(
                darkTheme = isDark,
                accentColor = appearance.accentColor,
                themePreset = themePreset,
                customBg = customBg,
                customFg = customFg,
                customAccent = customAccent
            ) {
                val startDestination = AppDestination.SPLASH
                var currentScreen by remember { mutableStateOf(startDestination) }
                val requestedDestination by deepLinkDestination.collectAsStateWithLifecycle()
                LaunchedEffect(requestedDestination) {
                    requestedDestination?.let { destination ->
                        currentScreen = destination
                        mutableDeepLinkDestination.value = null
                    }
                }
                val isBlurActive = DialogBlurState.isBlurActive
                val blurRadius by animateDpAsState(
                    targetValue = if (isBlurActive) 22.dp else 0.dp,
                    animationSpec = tween(durationMillis = 200),
                    label = "DialogBackgroundBlur"
                )

                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (blurRadius > 0.dp) Modifier.blur(blurRadius) else Modifier)
                ) {
                    AnimatedContent(
                        targetState = currentScreen,
                        transitionSpec = {
                            (fadeIn(animationSpec = tween(400)) + scaleIn(initialScale = 0.95f, animationSpec = tween(400)))
                                .togetherWith(fadeOut(animationSpec = tween(300)) + scaleOut(targetScale = 1.05f, animationSpec = tween(300)))
                        },
                        label = "ScreenTransition"
                    ) { destination ->
                        when (destination) {
                            AppDestination.SPLASH -> {
                                SplashScreen(
                                    viewModel = viewModel,
                                    onNavigateToChat = {
                                        currentScreen = AppDestination.WORKSPACE
                                    }
                                )
                            }
                            AppDestination.WORKSPACE -> {
                                WorkspaceScreen(viewModel = viewModel)
                            }
                        }
                    }
                }

                val otaState by viewModel.otaState.collectAsStateWithLifecycle()
                AppUpdateDialog(
                    state = otaState,
                    onDismiss = { viewModel.dismissOtaUpdate() },
                    onStartDownload = { info -> viewModel.startOtaDownload(this@MainActivity, info) },
                    onInstallApk = { downloadId ->
                        UpdateChecker(this@MainActivity).installApk(downloadId)
                        viewModel.dismissOtaUpdate()
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    /** Routes antigravity:// deep links into AuthDelegate and the ViewModel. */
    fun handleIncomingIntent(intent: Intent?) {
        AuthDelegate.handleDeepLinkIntent(
            intent = intent,
            onAuthCode = { code ->
                viewModel.addLog("DeepLink", "Received authorization code from deep link")
                viewModel.exchangeManualAuthCode(this, code)
            },
            onAuthSuccess = { email ->
                val suffix = email?.let { e -> ": $e" }.orEmpty()
                viewModel.addLog("DeepLink", "Auth success from deep link$suffix")
                viewModel.refreshAuthState(this)
            },
            onAuthError = { message ->
                viewModel.addLog("DeepLink", "Auth error from deep link: $message", true)
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            },
            onWorkspaceRequested = {
                mutableDeepLinkDestination.value = AppDestination.WORKSPACE
            },
            onUnknownLink = { raw ->
                if (!raw.isNullOrBlank()) {
                    viewModel.addLog("DeepLink", "Ignored unknown deep link: $raw")
                }
            }
        )
    }

    override fun onResume() {
        super.onResume()
        viewModel.onActivityResume(this)
    }
}

