package io.github.realrurichan.twowaypermitreader.model

enum class DocumentType(val title: String, val subtitle: String, val nfcMode: NfcMode) {
    HK_MACAO_PERMIT("往来港澳通行证", "签注记录、出入境记录与芯片读取", NfcMode.EXPERIMENTAL_ICAO),
}

enum class NfcMode { NONE, ICAO, EXPERIMENTAL_ICAO }
enum class FieldSource { OCR, MRZ, NFC, USER }

data class FieldValue(
    val value: String,
    val source: FieldSource,
    val confidence: Float? = null,
)

data class DocumentScanResult(
    val type: DocumentType,
    val fields: Map<String, FieldValue>,
    val portrait: ByteArray? = null,
    val integrity: IntegrityStatus = IntegrityStatus.NOT_CHECKED,
)

enum class IntegrityStatus { NOT_CHECKED, HASHES_VALID, HASH_MISMATCH, TRUST_NOT_ESTABLISHED }
