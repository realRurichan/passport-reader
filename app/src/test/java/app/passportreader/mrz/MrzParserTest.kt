package io.github.realrurichan.passportreader.mrz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MrzParserTest {
    private val sample = "P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<\nL898902C36UTO7408122F1204159ZE184226B<<<<<10"

    @Test fun parsesValidTd3() {
        val parsed = MrzParser.parseTd3(sample)
        assertTrue(parsed.isSuccess)
        assertEquals("L898902C3", parsed.getOrThrow().accessKey.documentNumber)
        assertEquals("ERIKSSON", parsed.getOrThrow().surname)
    }

    @Test fun computesCheckDigit() = assertEquals('6', MrzParser.checkDigit("L898902C3"))
    @Test fun rejectsBadCheckDigit() = assertTrue(MrzParser.parseTd3(sample.replace("C36", "C30")).isFailure)
}
