package io.github.realrurichan.passportreader.nfc

import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.sf.scuba.smartcards.CardService
import org.jmrtd.BACKey
import org.jmrtd.PassportService
import org.jmrtd.lds.CardAccessFile
import org.jmrtd.lds.PACEInfo
import org.jmrtd.lds.icao.DG1File
import org.jmrtd.lds.icao.DG11File
import org.jmrtd.lds.icao.DG12File
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
                val dg2Bytes = readOptional(service, PassportService.EF_DG2)
                val dg11Bytes = readOptional(service, PassportService.EF_DG11)
                val dg12Bytes = readOptional(service, PassportService.EF_DG12)
                val sodBytes = readOptional(service, PassportService.EF_SOD)
                val fields = parseFields(dg1Bytes, dg11Bytes, dg12Bytes)
                val groups = linkedMapOf("DG1" to dg1Bytes.size).apply {
                    dg2Bytes?.let { put("DG2", it.size) }
                    dg11Bytes?.let { put("DG11", it.size) }
                    dg12Bytes?.let { put("DG12", it.size) }
                    sodBytes?.let { put("SOD", it.size) }
                }
                val result = ChipReadResult(dg1Bytes, dg2Bytes, sodBytes, protocol, fields, groups)
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

private fun parseFields(dg1: ByteArray, dg11: ByteArray?, dg12: ByteArray?): Map<String, String> = linkedMapOf<String, String>().apply {
    val mrz = DG1File(ByteArrayInputStream(dg1)).mrzInfo
    putIfNotBlank("姓名", mrz.nameOfHolder.replace('<', ' '))
    putIfNotBlank("姓", mrz.primaryIdentifier.replace('<', ' '))
    putIfNotBlank("名", mrz.secondaryIdentifier.replace('<', ' '))
    putIfNotBlank("证件号码", mrz.documentNumber.replace("<", ""))
    putIfNotBlank("出生日期", mrz.dateOfBirth)
    putIfNotBlank("有效期", mrz.dateOfExpiry)
    putIfNotBlank("性别", mrz.gender.toString())
    putIfNotBlank("国籍", mrz.nationality)
    putIfNotBlank("签发国家/地区", mrz.issuingState)
    putIfNotBlank("个人号码", mrz.personalNumber?.replace("<", ""))
    dg11?.let { bytes -> runCatching { DG11File(ByteArrayInputStream(bytes)) }.getOrNull()?.let { file ->
        putIfNotBlank("完整姓名", file.nameOfHolder)
        putIfNotBlank("完整出生日期", file.fullDateOfBirth)
        putIfNotBlank("出生地", file.placeOfBirth?.joinToString(" / "))
        putIfNotBlank("补充个人号码", file.personalNumber)
        putIfNotBlank("其他有效证件号", file.otherValidTDNumbers?.joinToString(" / "))
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
