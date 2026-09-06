package io.github.realrurichan.passportreader.nfc

import io.github.realrurichan.passportreader.model.DocumentType
import io.github.realrurichan.passportreader.mrz.MrzAccessKey

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
)
