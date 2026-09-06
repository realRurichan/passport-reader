package io.github.realrurichan.twowaypermitreader.mrz

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

    @Test fun correctsCommonOcrLettersInNumericZones() {
        val noisy = sample.replace("1204159", "12O4159")
        assertTrue(MrzParser.parseTd3(noisy).isSuccess)
    }

    @Test fun parsesTd1AccessKey() {
        val td1 = "I<UTOD231458907<<<<<<<<<<<<<<<\n7408122F1204159UTO<<<<<<<<<<<6\nERIKSSON<<ANNA<MARIA<<<<<<<<<<"
        val key = MrzParser.parseTd1(td1).getOrThrow()
        assertEquals("D23145890", key.documentNumber)
        assertEquals("740812", key.birthDate)
        assertEquals("120415", key.expiryDate)
    }

    @Test fun parsesChineseSingleLinePermit() {
        val number = "C12345678"
        val expiry = "301231"
        val birth = "900101"
        val body = number + MrzParser.checkDigit(number) + "<" + expiry + MrzParser.checkDigit(expiry) + "<" + birth + MrzParser.checkDigit(birth)
        val line = "CS$body<${MrzParser.checkDigit(body.replace("<", ""))}"
        val key = MrzParser.parseSingleLinePermit(line).getOrThrow()
        assertEquals(number, key.documentNumber)
        assertEquals(expiry, key.expiryDate)
        assertEquals(birth, key.birthDate)
    }
}
