package pl.agh.meditracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Gs1Test {
    @Test
    fun ean13() = assertEquals(Scan("05909990296026"), parseScan("5909990296026"))

    @Test
    fun dataMatrixWithGs() = assertEquals(
        Scan("05909991389901", "2027-12-31", "AB123", "SN987654"),
        parseScan("010590999138990117271231" + "10AB123\u001D" + "21SN987654"),
    )

    @Test
    fun dataMatrixSerialBeforeBatchAndPrefix() = assertEquals(
        Scan("05909990296026", "2028-06-30", "L1", "X9"),
        parseScan("]d2\u001D0105909990296026" + "21X9\u001D" + "17280600" + "10L1"),
    )

    @Test
    fun humanReadable() = assertEquals(
        Scan("05909990296026", "2027-01-31", "B7", null),
        parseScan("(01)05909990296026(17)270131(10)B7"),
    )

    @Test
    fun humanReadableWithParensInBatch() = assertEquals(
        Scan("05909990296026", batch = "A(1)B"),
        parseScan("(01)05909990296026(10)A(1)B"),
    )

    @Test
    fun parensInBatchAndSerial() = assertEquals(
        Scan("05909990296026", batch = "A(1)B", serial = "S(2)"),
        parseScan("0105909990296026" + "10A(1)B\u001D" + "21S(2)"),
    )

    @Test
    fun endOfFebruaryInLeapYear() =
        assertEquals("2028-02-29", parseScan("0105909990296026" + "17280200")?.expiry)

    @Test
    fun invalidExpiryIsDropped() {
        assertEquals(Scan("05909990296026"), parseScan("0105909990296026" + "17281301"))
        assertEquals(Scan("05909990296026"), parseScan("0105909990296026" + "17270231"))
    }

    @Test
    fun unknownAiKeepsWhatWasRead() = assertEquals(
        Scan("05909990296026", "2028-06-30", "L1"),
        parseScan("0105909990296026" + "17280630" + "10L1\u001D" + "91ABC"),
    )

    @Test
    fun unknownAiBeforeGtin() = assertNull(parseScan("91ABC\u001D" + "0105909990296026"))

    @Test
    fun garbage() = assertNull(parseScan("hello"))
}
