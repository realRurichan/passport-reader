package io.github.realrurichan.twowaypermitreader.nfc

import io.github.realrurichan.twowaypermitreader.model.DocumentType
import io.github.realrurichan.twowaypermitreader.mrz.MrzAccessKey

enum class NfcStage { IDLE, TAG_DETECTED, CONNECTED, PACE, BAC, LDS_READING, COMPLETE, FAILED }

data class NfcReadState(
    val stage: NfcStage = NfcStage.IDLE,
    val message: String = "请将证件贴近手机 NFC 区域",
    val protocol: String? = null,
    val error: String? = null,
)

data class NfcReadRequest(
    val documentType: DocumentType,
    val accessKey: MrzAccessKey,
)

interface TravelDocumentChipReader {
    suspend fun read(tag: android.nfc.Tag, request: NfcReadRequest, update: (NfcReadState) -> Unit): ChipReadResult
}

data class ChipReadResult(
    val dg1: ByteArray,
    val dg2: ByteArray?,
    val sod: ByteArray?,
    val protocol: String,
    val fields: Map<String, String>,
    val dataGroups: Map<String, Int>,
    val declaredDataGroups: List<Int>,
    val probe: Map<String, String> = emptyMap(),
    val permitRecords: PermitRecords? = null,
)

/** 往来港澳通行证芯片内的签注与出入境验讫记录（隐藏记录文件 0x0111–0x0115）。 */
data class PermitRecords(
    val endorsements: List<EndorsementRecord> = emptyList(),
    val crossings: List<BorderCrossingRecord> = emptyList(),
    val counterNotes: List<String> = emptyList(),
)

data class EndorsementRecord(
    val documentNumber: String,
    val code: String,
    val target: String,
    /** 明细首 token 第三位的签注类别字母（G=个人旅游、D=逗留…），无法识别时为 null。 */
    val type: Char?,
    val typeLabel: String,
    val detailText: String,
    val detailRaw: String,
    val mac: String,
)

data class BorderCrossingRecord(
    val fid: Int,
    val terminal: String?,
    val timestamp: String?,
    val code: String?,
    val raw: String,
    /** 香港侧激活记录里入境时间之后的 8 位日期（批准逗留截止日），内地侧记录为 null。 */
    val approvedStayUntil: String? = null,
    /** 已识别的口岸（如 HZM=港珠澳大桥），未识别为 null。 */
    val port: String? = null,
)
