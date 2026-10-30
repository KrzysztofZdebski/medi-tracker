package pl.agh.meditracker

import java.time.YearMonth

private const val GS = '\u001D'

// Co da się odczytać z kodu na pudełku. Z EAN jest tylko GTIN, z DataMatrix (GS1) także reszta.
data class Scan(
    val gtin14: String,
    val expiry: String? = null, // RRRR-MM-DD, dzień 00 = koniec miesiąca
    val batch: String? = null,
    val serial: String? = null,
)

// długości AI o stałej długości; pozostałe kończą się znakiem GS albo końcem danych
private val FIXED = mapOf("01" to 14, "17" to 6)
private val VARIABLE = listOf("10", "21", "710", "711", "712", "713", "714")
private val BRACKETED_AI = Regex("\\((\\d{2,4})\\)")

// RRMMDD → RRRR-MM-DD; dzień 00 oznacza koniec miesiąca, niepoprawna data daje null
private fun parseExpiry(date: String): String? {
    if (!date.all(Char::isDigit)) return null
    val month = date.substring(2, 4).toInt()
    if (month !in 1..12) return null
    val yearMonth = YearMonth.of(2000 + date.take(2).toInt(), month)
    val day = date.substring(4).toInt()
    return when {
        day == 0 -> yearMonth.atEndOfMonth().toString()
        yearMonth.isValidDay(day) -> yearMonth.atDay(day).toString()
        else -> null
    }
}

fun parseScan(raw: String): Scan? {
    // zapis z nawiasami, (01)…(17)…, traktujemy jak separatory GS; nawiasy w danych zostają
    val s = raw.removePrefix("]d2").removePrefix("]C1").removePrefix("]Q3")
        .let { if (it.startsWith("(")) BRACKETED_AI.replace(it) { m -> GS + m.groupValues[1] } else it }
        .trimStart(GS)
    if (s.matches(Regex("\\d{8}|\\d{12,14}"))) return Scan(s.padStart(14, '0'))

    val fields = mutableMapOf<String, String>()
    var i = 0
    while (i < s.length) {
        val fixed = FIXED.keys.firstOrNull { s.startsWith(it, i) }
        if (fixed != null) {
            val end = i + 2 + FIXED.getValue(fixed)
            if (end > s.length) break
            fields[fixed] = s.substring(i + 2, end)
            i = if (end < s.length && s[end] == GS) end + 1 else end
            continue
        }
        // nieznanego AI nie da się przeskoczyć, zostaje to, co już odczytane
        val ai = VARIABLE.firstOrNull { s.startsWith(it, i) } ?: break
        val end = s.indexOf(GS, i).let { if (it < 0) s.length else it }
        fields[ai] = s.substring(i + ai.length, end)
        i = end + 1
    }

    val gtin = fields["01"]?.takeIf { it.all(Char::isDigit) } ?: return null
    return Scan(gtin, fields["17"]?.let(::parseExpiry), fields["10"], fields["21"])
}
