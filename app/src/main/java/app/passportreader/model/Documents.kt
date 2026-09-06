package io.github.realrurichan.passportreader.model

enum class DocumentType(val title: String, val subtitle: String, val nfcMode: NfcMode) {
    HK_MACAO_PERMIT("港澳通行证", "证件资料、背面签注与实验性芯片读取", NfcMode.EXPERIMENTAL_ICAO),
    PASSPORT("护照", "资料页、MRZ 与电子芯片", NfcMode.ICAO),
    HOME_RETURN_PERMIT("回乡证", "香港/澳门居民来往内地通行证", NfcMode.EXPERIMENTAL_ICAO),
    TAIWAN_COMPATRIOT_PERMIT("台胞证", "台湾居民来往大陆通行证", NfcMode.EXPERIMENTAL_ICAO),
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
