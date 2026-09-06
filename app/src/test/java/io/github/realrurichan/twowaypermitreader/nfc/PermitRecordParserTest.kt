package io.github.realrurichan.twowaypermitreader.nfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PermitRecordParserTest {
    private fun endorsementBytes(detail: String): ByteArray =
        ("CF6521415".padEnd(16) + "H032A1".padEnd(16) + detail.padEnd(47) + "DAA08F1D2B4BB25B")
            .toByteArray(Charsets.US_ASCII)

    @Test fun parsesEndorsementRecord() {
        val record = PermitRecordParser.parseEndorsement(endorsementBytes("A1G 20270829 440400 20260830 007"))
        assertNotNull(record)
        record!!
        assertEquals("H032A1", record.code)
        assertEquals("香港", record.target)
        assertEquals("A1G 20270829 440400 20260830 007", record.detailRaw)
        assertEquals("DAA08F1D2B4BB25B", record.mac)
    }

    @Test fun extractsEndorsementTypeLetter() {
        val g = PermitRecordParser.parseEndorsement(endorsementBytes("A1G 20270829 440400 20260830 007"))
        assertNotNull(g)
        assertEquals('G', g!!.type)
        assertEquals("个人旅游（G）", g.typeLabel)
        val d = PermitRecordParser.parseEndorsement(endorsementBytes("92D 20280831 2302 20240729090"))
        assertNotNull(d)
        assertEquals('D', d!!.type)
        assertEquals("逗留（D）", d.typeLabel)
    }

    @Test fun rejectsBlankEndorsement() {
        assertEquals(null, PermitRecordParser.parseEndorsement(ByteArray(79)))
    }

    @Test fun describesEndorsementDetailDatesAndPlace() {
        val text = PermitRecordParser.describeDetail("A1G 20270829 440400 20260830 007")
        assertTrue(text.contains("签发日期 2026-08-30"))
        assertTrue(text.contains("有效期至 2027-08-29"))
        assertTrue(text.contains("签发地 4404（珠海）"))
        assertTrue(text.contains("其余编码 007"))
    }

    /** 长数字串里嵌入的日期也能抠出来：20240729090 → 2024-07-29；签发地只认前 4 位。 */
    @Test fun describesStayEndorsementDetailWithEmbeddedDate() {
        val text = PermitRecordParser.describeDetail("92D 20280831 2302 20240729090")
        assertTrue(text.contains("签发日期 2024-07-29"))
        assertTrue(text.contains("有效期至 2028-08-31"))
        assertTrue(text.contains("签发地 2302（齐齐哈尔）"))
        assertTrue(text.contains("其余编码 090"))
    }

    /** 字段粘连（无空格）时也能抠出日期与签发地。 */
    @Test fun describesMergedDetailWithoutSpaces() {
        val text = PermitRecordParser.describeDetail("92D 202808312302 20240729090")
        assertTrue(text.contains("有效期至 2028-08-31"))
        assertTrue(text.contains("签发地 2302（齐齐哈尔）"))
    }

    @Test fun rejectsBlankCrossing() {
        assertEquals(null, PermitRecordParser.parseCrossing(0x0112, ByteArray(76)))
    }

    /** 真机原文：终端号紧贴时间戳、无分隔符，尾随二进制字节偶尔落进可打印区。 */
    @Test fun parsesRealCrossingRecord() {
        val bytes = "1H03120260727083635Z074771GC2331360530wX2:R+".padEnd(76).toByteArray(Charsets.US_ASCII)
        val record = PermitRecordParser.parseCrossing(0x0112, bytes)
        assertNotNull(record)
        record!!
        assertEquals(0x0112, record.fid)
        assertEquals("H031", record.terminal)
        assertEquals("2026-07-27 08:36:35", record.timestamp)
        assertEquals("Z074771GC2331360530", record.code)
    }

    @Test fun parsesRealCrossingShortCode() {
        val bytes = "1H03220260831132435HZM V3 1 2026090740Wq{C,$".padEnd(76).toByteArray(Charsets.US_ASCII)
        val record = PermitRecordParser.parseCrossing(0x0114, bytes)
        assertNotNull(record)
        record!!
        assertEquals("H032", record.terminal)
        assertEquals("2026-08-31 13:24:35", record.timestamp)
        assertEquals("港珠澳大桥（HZM）", record.port)
        assertEquals("2026090740W", record.code)
    }

    /** 香港侧激活记录：入境时间之后的 8 位日期是批准逗留截止日。 */
    @Test fun extractsApprovedStayFromHongKongActivation() {
        val bytes = "1H03220260831132435HZM V3 1 2026090740Wq{C,$".padEnd(76).toByteArray(Charsets.US_ASCII)
        val record = PermitRecordParser.parseCrossing(0x0114, bytes)
        assertNotNull(record)
        record!!
        assertEquals("2026-09-07", record.approvedStayUntil)
    }

    /** 内地侧激活记录没有第二个日期。 */
    @Test fun mainlandActivationHasNoApprovedStay() {
        val bytes = "1H03120260727083635Z074771GC2331360530wX2:R+".padEnd(76).toByteArray(Charsets.US_ASCII)
        val record = PermitRecordParser.parseCrossing(0x0112, bytes)
        assertNotNull(record)
        record!!
        assertEquals(null, record.approvedStayUntil)
    }

    @Test fun parsesCrossingWithSpaceSeparator() {
        val bytes = "31H031 20260727083635 Z074771GC23".padEnd(76).toByteArray(Charsets.US_ASCII)
        val record = PermitRecordParser.parseCrossing(0x0112, bytes)
        assertNotNull(record)
        record!!
        assertEquals("H031", record.terminal)
        assertEquals("2026-07-27 08:36:35", record.timestamp)
        assertEquals("Z074771GC23", record.code)
    }

    @Test fun rejectsCrossingWithoutValidStamp() {
        assertEquals(null, PermitRecordParser.parseCrossing(0x0112, "=cL".padEnd(76).toByteArray(Charsets.US_ASCII)))
    }
}
