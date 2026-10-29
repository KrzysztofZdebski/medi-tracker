package pl.agh.meditracker.feature.onboarding

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import pl.agh.meditracker.data.settings.SettingsStore

enum class OnboardingStep { Welcome, Reminders }

class OnboardingViewModel(private val settings: SettingsStore) : ViewModel() {
    var step by mutableStateOf(OnboardingStep.Welcome)
        private set

    fun goTo(target: OnboardingStep) {
        step = target
    }

    fun setReminders(enabled: Boolean) {
        settings.remindersEnabled = enabled
    }
}
