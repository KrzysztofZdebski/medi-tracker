package pl.agh.meditracker.feature.medications

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun MedicationsScreen(justAdded: Boolean, onAddMedication: () -> Unit, onOpenToday: () -> Unit) {
    Text("MedicationsScreen")
}
