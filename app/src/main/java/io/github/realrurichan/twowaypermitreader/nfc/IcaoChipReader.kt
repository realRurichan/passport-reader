package io.github.realrurichan.twowaypermitreader.nfc

import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.sf.scuba.smartcards.CardService
import net.sf.scuba.smartcards.CommandAPDU
import net.sf.scuba.smartcards.ResponseAPDU
import org.jmrtd.BACKey
import org.jmrtd.PassportService
import org.jmrtd.lds.CardAccessFile
import org.jmrtd.lds.PACEInfo
import org.jmrtd.lds.icao.DG1File
import org.jmrtd.lds.icao.DG11File
import org.jmrtd.lds.icao.DG12File
import org.jmrtd.lds.icao.COMFile
import org.jmrtd.lds.SODFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * ICAO 9303 reader. For Home Return/Taiwan Compatriot permits this is deliberately
 * best-effort: public sources do not establish that their chip application is ICAO LDS.
 */
class IcaoChipReader : TravelDocumentChipReader {
    override suspend fun read(tag: Tag, request: NfcReadRequest, update: (NfcReadState) -> Unit) =
        withContext(Dispatchers.IO) {
            val isoDep = requireNotNull(IsoDep.get(tag)) { "芯片不支持 ISO-DEP" }
            Log.i("DocumentNfc", "tag detected type=${request.documentType.name} tech=${tag.techList.joinToString()}")
            update(NfcReadState(NfcStage.TAG_DETECTED, "已发现芯片"))
            val probe = linkedMapOf<String, String>()
            probeEfDir(isoDep)?.let { probe["EF.DIR 应用列表"] = it }
            val cardService = CardService.getInstance(isoDep)
            val service = PassportService(cardService, 256, 224, false, false)
            var protocol = "BAC"
            try {
                cardService.open()
                service.open()
                update(NfcReadState(NfcStage.CONNECTED, "已连接芯片"))
                val key = BACKey(request.accessKey.documentNumber, request.accessKey.birthDate, request.accessKey.expiryDate)
                val paceSucceeded = runCatching {
                    val access = CardAccessFile(service.getInputStream(PassportService.EF_CARD_ACCESS))
                    val pace = access.securityInfos.filterIsInstance<PACEInfo>().first()
                    update(NfcReadState(NfcStage.PACE, "正在进行 PACE 鉴权"))
                    service.doPACE(key, pace.objectIdentifier, PACEInfo.toParameterSpec(pace.parameterId), null)
                    service.sendSelectApplet(true)
                }.isSuccess
                if (paceSucceeded) protocol = "PACE" else {
                    update(NfcReadState(NfcStage.BAC, "PACE 不可用，正在尝试 BAC"))
                    service.sendSelectApplet(false)
                    service.doBAC(key)
                }
                update(NfcReadState(NfcStage.LDS_READING, "正在读取身份数据"))
                val dg1Bytes = service.getInputStream(PassportService.EF_DG1).readFully()
                val comDeclaredGroups = readOptional(service, PassportService.EF_COM)?.let { bytes ->
                    runCatching { COMFile(ByteArrayInputStream(bytes)).tagList.map(org.jmrtd.lds.LDSFileUtil::lookupDataGroupNumberByTag) }.getOrNull()
                }.orEmpty()
                val dg2Pair = readWithStatus(service, PassportService.EF_DG2)
                val dg11Pair = readWithStatus(service, PassportService.EF_DG11)
                val (dg12Bytes, dg12Note) = readWithStatus(service, PassportService.EF_DG12)
                val (dg13Bytes, dg13Note) = readWithStatus(service, PassportService.EF_DG13)
                val dg2Bytes = dg2Pair.first
                val dg11Bytes = dg11Pair.first
                val dg12Endorsement = dg12Bytes?.let { bytes ->
                    runCatching { DG12File(ByteArrayInputStream(bytes)).endorsementsAndObservations }.getOrNull()
                }?.takeIf { it.isNotBlank() }
                probe["DG12（签注与备注）"] = when {
                    dg12Bytes != null -> "已读取 ${dg12Bytes.size} 字节" + (dg12Endorsement?.let { "：签注=$it" } ?: "")
                    else -> "不可访问（$dg12Note）"
                }
                probe["DG13（扩展自由数据）"] = when {
                    dg13Bytes != null -> "已读取 ${dg13Bytes.size} 字节：" + (decodeDg13(dg13Bytes) ?: hexPreview(dg13Bytes))
                    else -> "不可访问（$dg13Note）"
                }
                run {
                    val known = mutableMapOf(2 to dg2Pair, 11 to dg11Pair, 12 to (dg12Bytes to dg12Note), 13 to (dg13Bytes to dg13Note))
                    probe["数据组访问图"] = (2..16).joinToString(" ") { number ->
                        val (bytes, note) = known.getOrPut(number) { readWithStatus(service, (0x100 + number).toShort()) }
                        if (bytes != null) "DG$number✓${humanSize(bytes.size)}" else "DG$number✗($note)"
                    }
                }
                val sodBytes = readOptional(service, PassportService.EF_SOD)
                val sodDeclaredGroups = sodBytes?.let { bytes ->
                    runCatching { SODFile(ByteArrayInputStream(bytes)).dataGroupHashes.keys.sorted() }.getOrNull()
                }.orEmpty()
                val declaredGroups = (comDeclaredGroups + sodDeclaredGroups).distinct().sorted()
                val fields = parseFields(request, dg1Bytes, dg11Bytes, dg12Bytes)
                var permitRecords: PermitRecords? = null
                runCatching { probeHiddenRecords(service, probe) }.onSuccess { (hiddenFields, records) ->
                    fields.putAll(hiddenFields)
                    if (records.endorsements.isNotEmpty() || records.crossings.isNotEmpty() || records.counterNotes.isNotEmpty()) {
                        permitRecords = records
                    }
                }
                val groups = linkedMapOf("DG1" to dg1Bytes.size).apply {
                    dg2Bytes?.let { put("DG2", it.size) }
                    dg11Bytes?.let { put("DG11", it.size) }
                    dg12Bytes?.let { put("DG12", it.size) }
                    sodBytes?.let { put("SOD", it.size) }
                }
                val result = ChipReadResult(dg1Bytes, dg2Bytes, sodBytes, protocol, fields, groups, declaredGroups, probe, permitRecords)
                update(NfcReadState(NfcStage.COMPLETE, "芯片读取完成", protocol))
                result
            } catch (error: Exception) {
                Log.w("DocumentNfc", "read failed stage=${request.documentType.name} error=${error.javaClass.simpleName}")
                update(NfcReadState(NfcStage.FAILED, "无法读取芯片", protocol, error.message))
                throw error
            } finally {
                runCatching { service.close() }
                runCatching { cardService.close() }
            }
        }
}

private fun readOptional(service: PassportService, fileId: Short): ByteArray? =
    runCatching { service.getInputStream(fileId).readFully() }.getOrNull()

private fun readWithStatus(service: PassportService, fileId: Short): Pair<ByteArray?, String> =
    try {
        service.getInputStream(fileId).readFully() to ""
    } catch (error: Exception) {
        val sw = (error as? net.sf.scuba.smartcards.CardServiceException)?.sw
        val note = when {
            sw != null && sw != net.sf.scuba.smartcards.CardServiceException.SW_NONE -> String.format("SW=0x%04X", sw)
            else -> error.message ?: error.javaClass.simpleName
        }
        null to note
    }

private fun decodeDg13(bytes: ByteArray): String? {
    val parts = mutableListOf<String>()
    var index = 0
    var valid = true
    while (index < bytes.size && valid) {
        var tag = bytes[index].toInt() and 0xFF
        index++
        if (tag and 0x1F == 0x1F && index < bytes.size) {
            tag = (tag shl 8) or (bytes[index].toInt() and 0xFF)
            index++
        }
        if (index >= bytes.size) { valid = false; break }
        var length = bytes[index].toInt() and 0xFF
        index++
        if (length and 0x80 != 0) {
            val longBytes = length and 0x7F
            if (longBytes == 0 || longBytes > 2 || index + longBytes > bytes.size) { valid = false; break }
            length = 0
            repeat(longBytes) { length = (length shl 8) or (bytes[index].toInt() and 0xFF); index++ }
        }
        if (index + length > bytes.size) { valid = false; break }
        val value = bytes.copyOfRange(index, index + length)
        index += length
        val tagText = if (tag > 0xFF) String.format("0x%04X", tag) else String.format("0x%02X", tag)
        parts.add("$tagText（$length 字节）：" + (extractText(value) ?: hexPreview(value)))
        if (index >= bytes.size) break
    }
    return parts.joinToString("；").takeIf { valid && it.isNotBlank() }
}

private fun extractText(value: ByteArray): String? {
    if (value.isEmpty()) return null
    for (charset in listOf(Charsets.UTF_8, charset("GB2312"))) {
        val text = runCatching { String(value, charset) }.getOrNull() ?: continue
        if (text.contains('\uFFFD')) continue
        if (text.any { (it.code < 0x20 && it != '\n' && it != '\r' && it != '\t') || it.code == 0x7F }) continue
        if (text.trim().isNotBlank() && text.any { it.isLetterOrDigit() || it.code > 0x7F }) return text.trim()
    }
    return null
}

private fun hexPreview(bytes: ByteArray, limit: Int = 48): String =
    bytes.take(limit).joinToString(" ") { String.format("%02X", it) } + if (bytes.size > limit) "…" else ""

private fun humanSize(size: Int): String = if (size >= 1024) "${(size + 512) / 1024}KB" else "${size}B"

/** EF.DIR (2F00) is outside the eMRTD application and readable without authentication. */
private fun probeEfDir(isoDep: android.nfc.tech.IsoDep): String? = runCatching {
    val select = isoDep.transceive(byteArrayOf(0x00, 0xA4.toByte(), 0x00, 0x0C, 0x02, 0x2F, 0x00))
    if (select.size < 2 || select[select.size - 2] != 0x90.toByte() || select.last() != 0x00.toByte()) {
        return@runCatching "无 EF.DIR（SW=0x%02X%02X）".format(select[select.size - 2], select.last())
    }
    val output = StringBuilder()
    var offset = 0
    while (offset < 256) {
        val p1 = (offset shr 8).toByte(); val p2 = (offset and 0xFF).toByte()
        val response = isoDep.transceive(byteArrayOf(0x00, 0xB0.toByte(), p1, p2, 0x20))
        if (response.size < 2) break
        val data = response.copyOfRange(0, response.size - 2)
        val sw = ((response[response.size - 2].toInt() and 0xFF) shl 8) or (response.last().toInt() and 0xFF)
        if (data.isNotEmpty()) output.append(String(data, Charsets.ISO_8859_1))
        if (sw == 0x6282 || sw == 0x6B00 || data.isEmpty()) break
        offset += data.size
        if (sw and 0xFF00 == 0x6100) break
    }
    val raw = output.toString()
    val aids = Regex("4F(.)").findAll(raw).mapIndexedNotNull { index, match ->
        val length = match.groupValues[1][0].code
        val start = match.range.last + 1
        if (start + length <= raw.length) raw.substring(start, start + length)
            .map { String.format("%02X", it.code) }.joinToString("") else null
    }.distinct().toList()
    if (aids.isEmpty()) "EF.DIR 可读，但未解析到应用标识".takeIf { raw.isNotEmpty() } ?: "EF.DIR 为空"
    else "发现应用：${aids.joinToString("、")}"
}.getOrNull()

private fun parseFields(request: NfcReadRequest, dg1: ByteArray, dg11: ByteArray?, dg12: ByteArray?): MutableMap<String, String> = linkedMapOf<String, String>().apply {
    put("证件号码", request.accessKey.documentNumber)
    put("出生日期", request.accessKey.birthDate)
    put("有效期", request.accessKey.expiryDate)
    dg11?.let { bytes -> runCatching { DG11File(ByteArrayInputStream(bytes)) }.getOrNull()?.let { file ->
        putIfNotBlank("完整姓名", file.nameOfHolder)
        putIfNotBlank("完整出生日期", file.fullDateOfBirth)
        putIfNotBlank("出生地", file.placeOfBirth?.joinToString(" / "))
    } }
    dg12?.let { bytes -> runCatching { DG12File(ByteArrayInputStream(bytes)) }.getOrNull()?.let { file ->
        putIfNotBlank("签发机关", file.issuingAuthority)
        putIfNotBlank("签发日期", file.dateOfIssue)
        putIfNotBlank("签注与备注", file.endorsementsAndObservations)
        putIfNotBlank("出境要求", file.taxOrExitRequirements)
    } }
}

private fun MutableMap<String, String>.putIfNotBlank(label: String, value: String?) {
    value?.trim()?.takeIf { it.isNotEmpty() && it.any { char -> char != '<' } }?.let { put(label, it) }
}

private fun java.io.InputStream.readFully(): ByteArray = use { input ->
    val output = ByteArrayOutputStream()
    input.copyTo(output)
    output.toByteArray()
}

private fun probeHiddenRecords(service: PassportService, probe: MutableMap<String, String>): Pair<MutableMap<String, String>, PermitRecords> {
    val sender = service.secureMessagingAPDUSender
    fun apdu(hex: String): ResponseAPDU = sender.transmit(service.wrapper, CommandAPDU(hex.hexToBytes()))
    val fields = linkedMapOf<String, String>()
    val endorsements = mutableListOf<EndorsementRecord>()
    val crossings = mutableListOf<BorderCrossingRecord>()
    val counterNotes = mutableListOf<String>()
    val summary = mutableListOf<String>()
    for (fid in 0x0111..0x0115) {
        val select = runCatching { apdu(String.format("00A4020C02%04X", fid)) }.getOrNull() ?: continue
        if (select.sw != 0x9000 && select.sw shr 8 != 0x61) continue
        val records = mutableListOf<ByteArray>()
        for (record in 1..10) {
            var response = runCatching { apdu(String.format("00B2%02X0400", record)) }.getOrNull() ?: break
            if (response.sw shr 8 == 0x6C) {
                response = runCatching { apdu(String.format("00B2%02X04%02X", record, response.sw and 0xFF)) }.getOrNull() ?: break
            }
            if (response.sw != 0x9000) break
            records.add(response.data)
        }
        when (fid) {
            0x0111 -> records.forEach { bytes ->
                PermitRecordParser.parseEndorsement(bytes)?.let { endorsement ->
                    endorsements.add(endorsement)
                    fields["签注 ${endorsement.code}"] = endorsement.detailText
                }
            }
            0x0112, 0x0114 -> records.forEach { bytes ->
                PermitRecordParser.parseCrossing(fid, bytes)?.let { crossings.add(it) }
            }
            0x0113, 0x0115 -> if (records.isNotEmpty()) counterNotes.add(String.format("%04X ×%d 条", fid, records.size))
        }
        if (records.isNotEmpty() && fid != 0x0113 && fid != 0x0115) summary.add(String.format("%04X=%d条", fid, records.size))
    }
    if (summary.isNotEmpty()) probe["隐藏记录文件"] = summary.joinToString("，")
    return fields to PermitRecords(endorsements, crossings, counterNotes)
}

private fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0)
    return ByteArray(length / 2) { index ->
        ((Character.digit(this[index * 2], 16) shl 4) or Character.digit(this[index * 2 + 1], 16)).toByte()
    }
}
