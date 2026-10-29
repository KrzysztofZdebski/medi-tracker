package pl.agh.meditracker.feature.addmedication

import pl.agh.meditracker.model.DoseUnit
import java.time.DayOfWeek
import java.time.LocalTime

data class DetailsErrors(val name: String? = null, val strength: String? = null, val ean: String? = null) {
    val any get() = name != null || strength != null || ean != null
}

fun validateDetails(name: String, strength: String, ean: String) = DetailsErrors(
    name = if (name.isBlank()) "Wpisz nazwę leku." else null,
    strength = if (strength.isBlank()) "Wpisz moc leku." else null,
    ean = if (ean.isNotEmpty() && !ean.matches(Regex("[0-9]{8}|[0-9]{13}"))) "Wpisz poprawny 13-cyfrowy kod EAN." else null,
)

fun validateSchedule(days: Set<DayOfWeek>, times: List<LocalTime>): String? = when {
    times.isEmpty() -> "Dodaj przynajmniej jedną godzinę."
    days.isEmpty() -> "Wybierz przynajmniej jeden dzień."
    else -> null
}

fun isEanInput(input: String) = input.matches(Regex("[0-9]{8,14}"))

fun unitForForm(form: String?): DoseUnit {
    val text = form?.lowercase().orEmpty()
    return when {
        "tabl" in text -> DoseUnit.Tablet
        "kaps" in text -> DoseUnit.Capsule
        "sasz" in text -> DoseUnit.Sachet
        "krop" in text -> DoseUnit.Drop
        listOf("płyn", "syrop", "roztw", "zawies", "ml").any { it in text } -> DoseUnit.Ml
        else -> DoseUnit.Tablet
    }
}

fun initialStock(packageCount: Int?, packageSize: String): Int =
    packageCount ?: Regex("[0-9]+").find(packageSize)?.value?.toIntOrNull() ?: 30
