package io.github.realrurichan.passportreader.mrz

data class MrzAccessKey(
    val documentNumber: String,
    val birthDate: String,
    val expiryDate: String,
)

data class MrzResult(
    val accessKey: MrzAccessKey,
    val surname: String,
    val givenNames: String,
    val nationality: String,
    val sex: Char,
)

object MrzParser {
    private val weights = intArrayOf(7, 3, 1)

    fun parseTd3(text: String): Result<MrzResult> = runCatching {
        val lines = text.lines()
            .map(::normalizeOcrLine)
            .filter { it.length >= 40 }
            .map { it.take(44).padEnd(44, '<') }
        require(lines.size >= 2) { "需要两行 44 字符的 TD3 MRZ" }
        val first = lines[0].take(44)
        val second = normalizeNumericZones(lines[1].take(44))
        val number = second.substring(0, 9)
        val birth = second.substring(13, 19)
        val expiry = second.substring(21, 27)
        require(valid(number, second[9])) { "证件号码校验位错误" }
        require(valid(birth, second[19])) { "出生日期校验位错误" }
        require(valid(expiry, second[27])) { "有效期校验位错误" }
        val names = first.substring(5).split("<<", limit = 2)
        MrzResult(
            MrzAccessKey(number.replace("<", ""), birth, expiry),
            names.first().replace('<', ' ').trim(),
            names.getOrElse(1) { "" }.replace('<', ' ').trim(),
            second.substring(10, 13),
            second[20],
        )
    }

    fun parseAccessKey(text: String): Result<MrzAccessKey> {
        parseTd3(text).getOrNull()?.let { return Result.success(it.accessKey) }
        return parseTd1(text)
    }

    fun diagnosis(text: String): String {
        val td3 = parseTd3(text).exceptionOrNull()?.message ?: "TD3 通过"
        val td1 = parseTd1(text).exceptionOrNull()?.message ?: "TD1 通过"
        val lengths = text.lines().map(::normalizeOcrLine).filter(String::isNotBlank).map(String::length)
        return "$td3；$td1；识别行长度=${lengths.joinToString()}"
    }

    fun parseTd1(text: String): Result<MrzAccessKey> = runCatching {
        val lines = text.lines()
            .map(::normalizeOcrLine)
            .filter { it.length in 27..36 }
            .map { it.take(30).padEnd(30, '<') }
        require(lines.size >= 3) { "需要三行 30 字符的 TD1 MRZ" }
        val first = lines.windowed(3).firstOrNull { it[0].length == 30 && it[1].length == 30 }
            ?: error("未找到 TD1 MRZ")
        val line1 = normalizeTd1DocumentLine(first[0])
        val line2 = normalizeTd1DateLine(first[1])
        val documentNumber = line1.substring(5, 14)
        val birthDate = line2.substring(0, 6)
        val expiryDate = line2.substring(8, 14)
        require(valid(documentNumber, line1[14])) { "证件号码校验位错误" }
        require(valid(birthDate, line2[6])) { "出生日期校验位错误" }
        require(valid(expiryDate, line2[14])) { "有效期校验位错误" }
        MrzAccessKey(documentNumber.replace("<", ""), birthDate, expiryDate)
    }

    private fun normalizeOcrLine(line: String): String = line.uppercase()
        .replace('«', '<')
        .replace('〈', '<')
        .filter { it in 'A'..'Z' || it in '0'..'9' || it == '<' }

    private fun normalizeNumericZones(line: String): String {
        val chars = line.toCharArray()
        val numericPositions = (13..19) + (21..27) + listOf(9)
        numericPositions.forEach { index -> if (index < chars.size) chars[index] = ocrDigit(chars[index]) }
        return chars.concatToString()
    }

    private fun normalizeTd1DocumentLine(line: String): String {
        val chars = line.toCharArray()
        if (chars.size > 14) chars[14] = ocrDigit(chars[14])
        return chars.concatToString()
    }

    private fun normalizeTd1DateLine(line: String): String {
        val chars = line.toCharArray()
        ((0..6) + (8..14)).forEach { if (it < chars.size) chars[it] = ocrDigit(chars[it]) }
        return chars.concatToString()
    }

    private fun ocrDigit(value: Char): Char = when (value) {
        'O', 'Q', 'D' -> '0'
        'I', 'L' -> '1'
        'Z' -> '2'
        'S' -> '5'
        'G' -> '6'
        'B' -> '8'
        else -> value
    }

    fun valid(value: String, expected: Char): Boolean = checkDigit(value) == expected

    fun checkDigit(value: String): Char {
        val sum = value.uppercase().mapIndexed { index, c -> charValue(c) * weights[index % 3] }.sum()
        return ('0'.code + sum % 10).toChar()
    }

    private fun charValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'A'..'Z' -> c - 'A' + 10
        '<' -> 0
        else -> error("MRZ 包含无效字符: $c")
    }
}
