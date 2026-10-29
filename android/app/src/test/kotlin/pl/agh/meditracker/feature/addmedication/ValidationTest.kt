package pl.agh.meditracker.feature.addmedication

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.agh.meditracker.model.DoseUnit
import java.time.DayOfWeek
import java.time.LocalTime

class ValidationTest {
    @Test
    fun validDetailsHaveNoErrors() {
        assertFalse(validateDetails("Metformina", "500 mg", "").any)
        assertFalse(validateDetails("Metformina", "500 mg", "5901234123457").any)
        assertFalse(validateDetails("Metformina", "500 mg", "59012341").any)
    }

    @Test
    fun blankRequiredFieldsAreReported() {
        val errors = validateDetails(" ", "", "")
        assertEquals("Wpisz nazwę leku.", errors.name)
        assertEquals("Wpisz moc leku.", errors.strength)
        assertNull(errors.ean)
    }

    @Test
    fun eanMustHave8Or13Digits() {
        assertEquals("Wpisz poprawny 13-cyfrowy kod EAN.", validateDetails("A", "1", "5901234").ean)
        assertTrue(validateDetails("A", "1", "59012341234570").any)
    }

    @Test
    fun scheduleNeedsTimeAndDay() {
        val days = DayOfWeek.entries.toSet()
        assertEquals("Dodaj przynajmniej jedną godzinę.", validateSchedule(days, emptyList()))
        assertEquals("Wybierz przynajmniej jeden dzień.", validateSchedule(emptySet(), listOf(LocalTime.NOON)))
        assertNull(validateSchedule(days, listOf(LocalTime.NOON)))
    }

    @Test
    fun eanInputAccepts8To14Digits() {
        assertTrue(isEanInput("12345678"))
        assertTrue(isEanInput("12345678901234"))
        assertFalse(isEanInput("1234567"))
        assertFalse(isEanInput("12345678a"))
    }

    @Test
    fun catalogFormMapsToUnit() {
        assertEquals(DoseUnit.Tablet, unitForForm("tabletki powlekane"))
        assertEquals(DoseUnit.Capsule, unitForForm("kapsułki twarde"))
        assertEquals(DoseUnit.Sachet, unitForForm("Saszetki"))
        assertEquals(DoseUnit.Drop, unitForForm("krople doustne"))
        assertEquals(DoseUnit.Ml, unitForForm("syrop"))
        assertEquals(DoseUnit.Tablet, unitForForm(null))
    }

    @Test
    fun initialStockUsesPackageCountThenSizeThenDefault() {
        assertEquals(60, initialStock(60, "30 tabletek"))
        assertEquals(28, initialStock(null, "28 tabletek"))
        assertEquals(30, initialStock(null, ""))
    }
}
