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
