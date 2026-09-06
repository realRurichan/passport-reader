package io.github.realrurichan.passportreader.nfc

import android.nfc.Tag
import android.nfc.tech.IsoDep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.sf.scuba.smartcards.CardService
import org.jmrtd.BACKey
import org.jmrtd.PassportService
import org.jmrtd.lds.CardAccessFile
import org.jmrtd.lds.PACEInfo
import java.io.ByteArrayOutputStream

/**
 * ICAO 9303 reader. For Home Return/Taiwan Compatriot permits this is deliberately
 * best-effort: public sources do not establish that their chip application is ICAO LDS.
 */
class IcaoChipReader : TravelDocumentChipReader {
    override suspend fun read(tag: Tag, request: NfcReadRequest, update: (NfcReadState) -> Unit) =
        withContext(Dispatchers.IO) {
            val isoDep = requireNotNull(IsoDep.get(tag)) { "芯片不支持 ISO-DEP" }
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
                val result = ChipReadResult(
                    dg1 = service.getInputStream(PassportService.EF_DG1).readFully(),
                    dg2 = runCatching { service.getInputStream(PassportService.EF_DG2).readFully() }.getOrNull(),
                    sod = runCatching { service.getInputStream(PassportService.EF_SOD).readFully() }.getOrNull(),
                    protocol = protocol,
                )
                update(NfcReadState(NfcStage.COMPLETE, "芯片读取完成", protocol))
                result
            } catch (error: Exception) {
                update(NfcReadState(NfcStage.FAILED, "无法读取芯片", protocol, error.message))
                throw error
            } finally {
                runCatching { service.close() }
                runCatching { cardService.close() }
            }
        }
}

private fun java.io.InputStream.readFully(): ByteArray = use { input ->
    val output = ByteArrayOutputStream()
    input.copyTo(output)
    output.toByteArray()
}
