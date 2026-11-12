package pl.agh.meditracker.feature.account

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.agh.meditracker.data.account.AccountState
import pl.agh.meditracker.data.account.crypto.RecoveryCode
import pl.agh.meditracker.feature.onboarding.OnboardingLayout
import pl.agh.meditracker.ui.components.PrimaryButton
import pl.agh.meditracker.ui.components.SecondaryButton

/**
 * Pokazuje kod z rejestracji albo (po resecie bez kodu) nowy kod z [AuthViewModel.wipeCode]. [onDone] woła tylko po
 * „Dalej”; przy rejestracji najpierw potwierdza kod w repozytorium (wtedy startuje pierwsza kopia).
 */
@Composable
fun RecoveryCodeScreen(viewModel: AuthViewModel, onDone: () -> Unit) {
    val context = LocalContext.current
    val state by viewModel.accountState.collectAsState()
    val current = viewModel.wipeCode ?: (state as? AccountState.ShowRecoveryCode)?.code
    // po potwierdzeniu stan zmienia się jeszcze przed nawigacją; ekran nie może wtedy zniknąć w połowie
    var shown by remember { mutableStateOf(current) }
    if (current != null && current != shown) shown = current
    val code = shown
    if (code == null) {
        LaunchedEffect(Unit) { onDone() }
        return
    }
    // kodu nie pokażemy drugi raz, więc Wstecz go nie zamyka; wyjście to „Dalej” po zaznaczeniu „Zapisałem kod”
    BackHandler {}
    RecoveryCodeContent(
        code = code,
        busy = viewModel.busy,
        onCopy = { copyCode(context, code.display()) },
        onShare = { shareCode(context, code.display()) },
        onNext = {
            if (viewModel.wipeCode != null) onDone() else viewModel.acknowledgeRecoveryCode(onDone)
        },
    )
}

/** Nowy ekran „Zapisz kod odzyskiwania”; „Dalej” czeka na zaznaczenie „Zapisałem kod”. */
@Composable
fun RecoveryCodeContent(code: RecoveryCode, busy: Boolean, onCopy: () -> Unit, onShare: () -> Unit, onNext: () -> Unit) {
    var saved by rememberSaveable { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    OnboardingLayout(
        title = "Kod odzyskiwania",
        icon = Icons.Outlined.Lock,
        headline = "Zapisz kod odzyskiwania",
        body = "To jedyny sposób na odzyskanie danych, jeśli zapomnisz hasła. Zapisz go w bezpiecznym miejscu. Nie pokażemy go ponownie.",
    ) {
        Surface(
            Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = colors.surface,
            border = BorderStroke(1.dp, colors.outlineVariant),
        ) {
            Text(
                code.display(),
                Modifier.padding(horizontal = 12.dp, vertical = 24.dp),
                style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace, fontSize = 18.sp, letterSpacing = 0.sp, fontWeight = FontWeight.Medium),
                color = colors.onSurface, textAlign = TextAlign.Center,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton(if (copied) "Skopiowano" else "Kopiuj", { copied = true; onCopy() }, Modifier.weight(1f))
            SecondaryButton("Udostępnij", onShare, Modifier.weight(1f))
        }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(saved, role = Role.Checkbox, onValueChange = { saved = it }),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Checkbox(saved, onCheckedChange = null)
            Text("Zapisałem kod", style = MaterialTheme.typography.bodyLarge)
        }
        PrimaryButton("Dalej", onNext, enabled = saved, loading = busy)
    }
}

private fun copyCode(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText("Kod odzyskiwania", text)
    if (Build.VERSION.SDK_INT >= 33) {
        // schowek nie pokazuje podglądu ani nie synchronizuje kodu
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    clipboard.setPrimaryClip(clip)
}

private fun shareCode(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, "Udostępnij kod odzyskiwania").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
