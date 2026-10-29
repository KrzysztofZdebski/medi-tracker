package pl.agh.meditracker.feature.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import pl.agh.meditracker.core.appViewModel
import pl.agh.meditracker.ui.components.AppTopBar
import pl.agh.meditracker.ui.components.IconBadge

@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val viewModel = appViewModel { OnboardingViewModel(it.settings) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.setReminders(granted)
        onFinished()
    }
    when (viewModel.step) {
        OnboardingStep.Welcome -> WelcomeContent(onStart = { viewModel.goTo(OnboardingStep.Reminders) })
        OnboardingStep.Reminders -> {
            BackHandler { viewModel.goTo(OnboardingStep.Welcome) }
            RemindersContent(
                onBack = { viewModel.goTo(OnboardingStep.Welcome) },
                onEnable = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        viewModel.setReminders(true)
                        onFinished()
                    }
                },
                onSkip = onFinished,
            )
        }
    }
}

@Composable
internal fun OnboardingLayout(
    title: String,
    icon: ImageVector,
    headline: String,
    body: String,
    onBack: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AppTopBar(title = title, onBack = onBack)
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(32.dp))
            IconBadge(icon, Modifier.clip(MaterialTheme.shapes.large), size = 88.dp)
            Text(headline, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onSurface)
            Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}
