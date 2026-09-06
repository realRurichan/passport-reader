package io.github.realrurichan.twowaypermitreader.nfc

import java.util.Calendar

/**
 * 往来港澳通行证芯片隐藏记录文件的纯解析逻辑（无 Android 依赖，便于单测）。
 *
 * 0x0111 签注记录（79B/条）：证件号[16] + 签注代码[16] + 明细[~47] + MAC[16 ASCII]。
 * 0x0112/0x0114 出入境验讫记录（76B/条）：终端 + 14 位时间戳 + 验讫代码等。
 * 0x0113/0x0115 计数器文件。
 */
object PermitRecordParser {
    /** 签发地为 GB/T 2260 市级 4 位区划码（如 4404=珠海、2302=齐齐哈尔），6 位形式后两位是补零，只取前 4 位。 */
    private val issueCities = mapOf(
        // 直辖市
        "1101" to "北京", "1201" to "天津", "3101" to "上海", "5001" to "重庆", "5002" to "重庆",
        // 河北
        "1301" to "石家庄", "1302" to "唐山", "1303" to "秦皇岛", "1304" to "邯郸", "1305" to "邢台",
        "1306" to "保定", "1307" to "张家口", "1308" to "承德", "1309" to "沧州", "1310" to "廊坊", "1311" to "衡水",
        // 山西
        "1401" to "太原", "1402" to "大同", "1403" to "阳泉", "1404" to "长治", "1405" to "晋城",
        "1406" to "朔州", "1407" to "晋中", "1408" to "运城", "1409" to "忻州", "1410" to "临汾", "1411" to "吕梁",
        // 内蒙古
        "1501" to "呼和浩特", "1502" to "包头", "1503" to "乌海", "1504" to "赤峰", "1505" to "通辽",
        "1506" to "鄂尔多斯", "1507" to "呼伦贝尔", "1508" to "巴彦淖尔", "1509" to "乌兰察布",
        "1522" to "兴安盟", "1525" to "锡林郭勒盟", "1529" to "阿拉善盟",
        // 辽宁
        "2101" to "沈阳", "2102" to "大连", "2103" to "鞍山", "2104" to "抚顺", "2105" to "本溪",
        "2106" to "丹东", "2107" to "锦州", "2108" to "营口", "2109" to "阜新", "2110" to "辽阳",
        "2111" to "盘锦", "2112" to "铁岭", "2113" to "朝阳", "2114" to "葫芦岛",
        // 吉林
        "2201" to "长春", "2202" to "吉林", "2203" to "四平", "2204" to "辽源", "2205" to "通化",
        "2206" to "白山", "2207" to "松原", "2208" to "白城", "2224" to "延边",
        // 黑龙江
        "2301" to "哈尔滨", "2302" to "齐齐哈尔", "2303" to "鸡西", "2304" to "鹤岗", "2305" to "双鸭山",
        "2306" to "大庆", "2307" to "伊春", "2308" to "佳木斯", "2309" to "七台河", "2310" to "牡丹江",
        "2311" to "黑河", "2312" to "绥化", "2327" to "大兴安岭",
        // 江苏
        "3201" to "南京", "3202" to "无锡", "3203" to "徐州", "3204" to "常州", "3205" to "苏州",
        "3206" to "南通", "3207" to "连云港", "3208" to "淮安", "3209" to "盐城", "3210" to "扬州",
        "3211" to "镇江", "3212" to "泰州", "3213" to "宿迁",
        // 浙江
        "3301" to "杭州", "3302" to "宁波", "3303" to "温州", "3304" to "嘉兴", "3305" to "湖州",
        "3306" to "绍兴", "3307" to "金华", "3308" to "衢州", "3309" to "舟山", "3310" to "台州", "3311" to "丽水",
        // 安徽
        "3401" to "合肥", "3402" to "芜湖", "3403" to "蚌埠", "3404" to "淮南", "3405" to "马鞍山",
        "3406" to "淮北", "3407" to "铜陵", "3408" to "安庆", "3410" to "黄山", "3411" to "滁州",
        "3412" to "阜阳", "3413" to "宿州", "3414" to "六安", "3415" to "亳州", "3416" to "池州", "3417" to "宣城",
        // 福建
        "3501" to "福州", "3502" to "厦门", "3503" to "莆田", "3504" to "三明", "3505" to "泉州",
        "3506" to "漳州", "3507" to "南平", "3508" to "龙岩", "3509" to "宁德",
        // 江西
        "3601" to "南昌", "3602" to "景德镇", "3603" to "萍乡", "3604" to "九江", "3605" to "新余",
        "3606" to "鹰潭", "3607" to "赣州", "3608" to "吉安", "3609" to "宜春", "3610" to "抚州", "3611" to "上饶",
        // 山东
        "3701" to "济南", "3702" to "青岛", "3703" to "淄博", "3704" to "枣庄", "3705" to "东营",
        "3706" to "烟台", "3707" to "潍坊", "3708" to "济宁", "3709" to "泰安", "3710" to "威海",
        "3711" to "日照", "3713" to "临沂", "3714" to "德州", "3715" to "聊城", "3716" to "滨州", "3717" to "菏泽",
        // 河南
        "4101" to "郑州", "4102" to "开封", "4103" to "洛阳", "4104" to "平顶山", "4105" to "安阳",
        "4106" to "鹤壁", "4107" to "新乡", "4108" to "焦作", "4109" to "濮阳", "4110" to "许昌",
        "4111" to "漯河", "4112" to "三门峡", "4113" to "南阳", "4114" to "商丘", "4115" to "信阳",
        "4116" to "周口", "4117" to "驻马店", "4190" to "济源",
        // 湖北
        "4201" to "武汉", "4202" to "黄石", "4203" to "十堰", "4205" to "宜昌", "4206" to "襄阳",
        "4207" to "鄂州", "4208" to "荆门", "4209" to "孝感", "4210" to "荆州", "4211" to "黄冈",
        "4212" to "咸宁", "4213" to "随州", "4228" to "恩施", "4290" to "省直辖县",
        // 湖南
        "4301" to "长沙", "4302" to "株洲", "4303" to "湘潭", "4304" to "衡阳", "4305" to "邵阳",
        "4306" to "岳阳", "4307" to "常德", "4308" to "张家界", "4309" to "益阳", "4310" to "郴州",
        "4311" to "永州", "4312" to "怀化", "4313" to "娄底", "4331" to "湘西",
        // 广东
        "4401" to "广州", "4402" to "韶关", "4403" to "深圳", "4404" to "珠海", "4405" to "汕头",
        "4406" to "佛山", "4407" to "江门", "4408" to "湛江", "4409" to "茂名", "4412" to "肇庆",
        "4413" to "惠州", "4414" to "梅州", "4415" to "汕尾", "4416" to "河源", "4417" to "阳江",
        "4418" to "清远", "4419" to "东莞", "4420" to "中山", "4451" to "潮州", "4452" to "揭阳", "4453" to "云浮",
        // 广西
        "4501" to "南宁", "4502" to "柳州", "4503" to "桂林", "4504" to "梧州", "4505" to "北海",
        "4506" to "防城港", "4507" to "钦州", "4508" to "贵港", "4509" to "玉林", "4510" to "百色",
        "4511" to "贺州", "4512" to "河池", "4513" to "来宾", "4514" to "崇左",
        // 海南
        "4601" to "海口", "4602" to "三亚", "4603" to "三沙", "4604" to "儋州", "4690" to "省直辖县",
        // 四川
        "5101" to "成都", "5103" to "自贡", "5104" to "攀枝花", "5105" to "泸州", "5106" to "德阳",
        "5107" to "绵阳", "5108" to "广元", "5109" to "遂宁", "5110" to "内江", "5111" to "乐山",
        "5113" to "南充", "5114" to "眉山", "5115" to "宜宾", "5116" to "广安", "5117" to "达州",
        "5118" to "雅安", "5119" to "巴中", "5120" to "资阳", "5132" to "阿坝", "5133" to "甘孜", "5134" to "凉山",
        // 贵州
        "5201" to "贵阳", "5202" to "六盘水", "5203" to "遵义", "5204" to "安顺", "5205" to "毕节",
        "5206" to "铜仁", "5223" to "黔西南", "5226" to "黔东南", "5227" to "黔南",
        // 云南
        "5301" to "昆明", "5303" to "曲靖", "5304" to "玉溪", "5305" to "保山", "5306" to "昭通",
        "5307" to "丽江", "5308" to "普洱", "5309" to "临沧", "5323" to "楚雄", "5325" to "红河",
        "5326" to "文山", "5328" to "西双版纳", "5329" to "大理", "5331" to "德宏", "5333" to "怒江", "5334" to "迪庆",
        // 西藏
        "5401" to "拉萨", "5402" to "日喀则", "5403" to "昌都", "5404" to "林芝", "5405" to "山南",
        "5406" to "那曲", "5425" to "阿里",
        // 陕西
        "6101" to "西安", "6102" to "铜川", "6103" to "宝鸡", "6104" to "咸阳", "6105" to "渭南",
        "6106" to "延安", "6107" to "汉中", "6108" to "榆林", "6109" to "安康", "6110" to "商洛",
        // 甘肃
        "6201" to "兰州", "6202" to "嘉峪关", "6203" to "金昌", "6204" to "白银", "6205" to "天水",
        "6206" to "武威", "6207" to "张掖", "6208" to "平凉", "6209" to "酒泉", "6210" to "庆阳",
        "6211" to "定西", "6229" to "陇南", "6230" to "临夏", "6231" to "甘南",
        // 青海
        "6301" to "西宁", "6302" to "海东", "6322" to "海北", "6323" to "黄南", "6325" to "海南",
        "6326" to "果洛", "6327" to "玉树", "6328" to "海西",
        // 宁夏
        "6401" to "银川", "6402" to "石嘴山", "6403" to "吴忠", "6404" to "固原", "6405" to "中卫",
        // 新疆
        "6501" to "乌鲁木齐", "6502" to "克拉玛依", "6504" to "吐鲁番", "6505" to "哈密",
        "6523" to "昌吉", "6527" to "博尔塔拉", "6528" to "巴音郭楞", "6529" to "阿克苏",
        "6530" to "克孜勒苏", "6531" to "喀什", "6532" to "和田", "6540" to "伊犁",
        "6542" to "塔城", "6543" to "阿勒泰", "6590" to "自治区直辖县",
    )
    private val issueProvinces = mapOf(
        "11" to "北京", "12" to "天津", "13" to "河北", "14" to "山西", "15" to "内蒙古",
        "21" to "辽宁", "22" to "吉林", "23" to "黑龙江", "31" to "上海", "32" to "江苏",
        "33" to "浙江", "34" to "安徽", "35" to "福建", "36" to "江西", "37" to "山东",
        "41" to "河南", "42" to "湖北", "43" to "湖南", "44" to "广东", "45" to "广西",
        "46" to "海南", "50" to "重庆", "51" to "四川", "52" to "贵州", "53" to "云南",
        "54" to "西藏", "61" to "陕西", "62" to "甘肃", "63" to "青海", "64" to "宁夏", "65" to "新疆",
    )

    private fun placeLabel(token: String): String? {
        val code = token.take(4)
        return issueCities[code]?.let { "$code（$it）" }
            ?: issueProvinces[code.take(2)]?.let { "$code（$it）" }
    }

    /** 签注类别（明细首 token 第三位字母）：如 A1G → G=个人旅游，92D → D=逗留。 */
    private val endorsementTypeNames = mapOf(
        'D' to "逗留", 'G' to "个人旅游", 'T' to "团队旅游", 'S' to "商务", 'Q' to "探亲", 'F' to "其他",
    )

    /** 香港入境处管制站/口岸三字码 → 中文名（参考入境处管制站列表）。 */
    private val portNames = mapOf(
        "HKG" to "香港国际机场",
        "LOW" to "罗湖", "LWW" to "罗湖",
        "LMC" to "落马洲（皇岗）", "LMS" to "落马洲支线（福田）", "LFB" to "落马洲支线（福田）",
        "SZB" to "深圳湾",
        "WRL" to "高铁西九龙站", "WKS" to "高铁西九龙站",
        "HZM" to "港珠澳大桥",
        "HYW" to "香园围（莲塘）",
        "MFT" to "港澳客轮码头",
        "CFT" to "中国客运码头",
    )

    fun parseEndorsement(bytes: ByteArray): EndorsementRecord? {
        if (bytes.size < 48) return null
        val documentNumber = asciiField(bytes, 0, 16).trim()
        val code = asciiField(bytes, 16, 32).trim()
        if (code.isBlank()) return null
        val detail = asciiField(bytes, 32, bytes.size - 16).trim()
        val mac = asciiField(bytes, bytes.size - 16, bytes.size).trim()
        val type = detail.split(Regex("\\s+")).firstOrNull()
            ?.getOrNull(2)?.takeIf { it.isLetter() }
        return EndorsementRecord(
            documentNumber = documentNumber,
            code = code,
            target = when (code.firstOrNull()) {
                'H' -> "香港"
                'M' -> "澳门"
                else -> "未知"
            },
            type = type,
            typeLabel = type?.let { "${endorsementTypeNames[it] ?: "未知类别"}（$it）" } ?: "",
            detailText = describeDetail(detail),
            detailRaw = detail,
            mac = mac,
        )
    }

    /**
     * 验讫记录分两类（真机数据）：
     * - 签注使用/历史记录：`1H03120260727083635Z074771GC23…`，1H0xx 为签注次数编码；
     * - 香港入境激活记录：`1H03220260831132435HZM…20260907…`，14 位为香港入境时间，
     *   其后的 8 位日期为批准逗留截止日。
     * 终端/次数编码紧贴时间戳、无分隔符，不能用简单 \d{14} 匹配——
     * 要在每段连续数字里滑动窗口，取第一段年月日时分秒都合法的 14 位。
     * 找不到合法时间戳的记录视为空记录。
     */
    fun parseCrossing(fid: Int, bytes: ByteArray): BorderCrossingRecord? {
        val text = asciiField(bytes, 0, bytes.size).trim()
        if (text.isBlank()) return null
        val (stampStart, stamp) = findStamp(text) ?: return null
        val terminal = Regex("([0-9A-Z]{4,6})\\s*$").find(text.substring(0, stampStart))
            ?.groupValues?.get(1)
            ?.dropWhile { !it.isLetter() }
        val rest = text.substring(stampStart + stamp.length)
        val approvedStayUntil = findCompactDate(rest)?.let { formatDate(it.second) }
        val tokens = Regex("[A-Z0-9]{3,}").findAll(rest).map { it.value }.toList()
        val portCode = tokens.firstOrNull { it in portNames }
        val code = tokens
            .filter { it != terminal && it != portCode }
            .joinToString("、")
            .takeIf { it.isNotBlank() }
        return BorderCrossingRecord(
            fid = fid,
            terminal = terminal,
            timestamp = formatStamp(stamp),
            code = code,
            raw = text,
            approvedStayUntil = approvedStayUntil,
            port = portCode?.let { "${portNames.getValue(it)}（$it）" },
        )
    }

    /** 在每段连续数字里滑动窗口，找第一个“年月日时分秒都合法”的 14 位时间戳，返回其起始下标与值。 */
    private fun findStamp(text: String): Pair<Int, String>? {
        Regex("\\d{9,}").findAll(text).forEach { run ->
            val digits = run.value
            for (offset in 0..digits.length - 14) {
                val candidate = digits.substring(offset, offset + 14)
                if (candidate.isValidStamp()) return run.range.first + offset to candidate
            }
        }
        return null
    }

    private fun String.isValidStamp(): Boolean {
        val (date, time) = take(8) to takeLast(6)
        val year = date.substring(0, 4).toIntOrNull() ?: return false
        val month = date.substring(4, 6).toIntOrNull() ?: return false
        val day = date.substring(6, 8).toIntOrNull() ?: return false
        val hour = time.substring(0, 2).toIntOrNull() ?: return false
        val minute = time.substring(2, 4).toIntOrNull() ?: return false
        val second = time.substring(4, 6).toIntOrNull() ?: return false
        return year in 2000..2100 && month in 1..12 && day in 1..31 &&
            hour <= 23 && minute <= 59 && second <= 59
    }

    /** 在长数字串里滑动窗口找第一个合法的 8 位日期，返回（串内起始下标, 日期, 窗口外剩余数字）。 */
    private fun findCompactDate(text: String): Triple<Int, String, String>? {
        Regex("\\d{8,}").findAll(text).forEach { run ->
            val digits = run.value
            for (offset in 0..digits.length - 8) {
                val candidate = digits.substring(offset, offset + 8)
                if (candidate.isValidDate()) {
                    return Triple(run.range.first + offset, candidate, digits.removeRange(offset, offset + 8))
                }
            }
        }
        return null
    }

    private fun String.isValidDate(): Boolean {
        val year = substring(0, 4).toIntOrNull() ?: return false
        val month = substring(4, 6).toIntOrNull() ?: return false
        val day = substring(6, 8).toIntOrNull() ?: return false
        return year in 2000..2100 && month in 1..12 && day in 1..31
    }

    /**
     * 把签注明细原文翻译成人话。不依赖空格分词（真机字段可能粘连）：
     * 在每段连续数字里滑动窗口——先抠合法 8 位日期（签发日期/有效期至），
     * 再在剩余数字里抠 4 位签发地区划码；开头的短字母数字 token（A1G/92D）是签注类别，
     * 标题已展示不再重复；其余残段归入“其余编码”。
     */
    fun describeDetail(detail: String): String {
        if (detail.isBlank()) return ""
        val now = Calendar.getInstance().let {
            String.format("%04d%02d%02d", it.get(Calendar.YEAR), it.get(Calendar.MONTH) + 1, it.get(Calendar.DAY_OF_MONTH))
        }
        val typeToken = Regex("[A-Z0-9]{2,4}").find(detail)?.value
            ?.takeIf { it.length <= 4 && it.any(Char::isLetter) }
        val body = typeToken?.let { detail.replaceFirst(it, " ") } ?: detail
        val dates = mutableListOf<String>()
        val places = mutableListOf<String>()
        val leftovers = mutableListOf<String>()
        Regex("\\d+").findAll(body).forEach { run ->
            val s = run.value
            // 独立的 4/6 位数字段就是签发地（如 2302、440400）
            if (s.length == 4 || s.length == 6) {
                placeLabel(s)?.let { places.add(it); return@forEach }
            }
            val rest = StringBuilder()
            var i = 0
            while (i < s.length) {
                if (i + 8 <= s.length) {
                    val window = s.substring(i, i + 8)
                    if (window.isValidDate()) {
                        dates.add(window); i += 8
                        // 有效期日期后紧邻的 4 位是签发地，只认市级码
                        if (i + 4 <= s.length) {
                            val code = s.substring(i, i + 4)
                            issueCities[code]?.let { places.add("$code（$it）"); i += 4 }
                        }
                        continue
                    }
                }
                rest.append(s[i]); i++
            }
            // 剩余数字里仍可能藏着签发地：只扫市级 4 位码（不用省份兜底，避免 3123→上海 这类误报）
            var rem = rest.toString()
            val keep = StringBuilder()
            var j = 0
            while (j + 4 <= rem.length) {
                val code = rem.substring(j, j + 4)
                val city = issueCities[code]
                if (city != null) { places.add("$code（$city）"); j += 4 } else { keep.append(rem[j]); j++ }
            }
            keep.append(rem.substring(j))
            if (keep.isNotEmpty()) leftovers.add(keep.toString())
        }
        val sorted = dates.distinct().sorted()
        val parts = when {
            sorted.size >= 2 -> mutableListOf(
                "签发日期 ${formatDate(sorted.first())}",
                *(sorted.drop(1).dropLast(1).map { "日期 ${formatDate(it)}" }.toTypedArray()),
                "有效期至 ${formatDate(sorted.last())}",
            )
            sorted.size == 1 -> mutableListOf(
                if (sorted[0] >= now) "有效期至 ${formatDate(sorted[0])}" else "签发日期 ${formatDate(sorted[0])}",
            )
            else -> mutableListOf()
        }
        places.distinct().forEach { parts.add("签发地 $it") }
        val kept = leftovers.filter { it.any { c -> c != '0' } }
        if (kept.isNotEmpty()) parts.add("其余编码 ${kept.joinToString("、")}")
        return parts.joinToString(" · ")
    }

    private fun formatDate(compact: String): String =
        "${compact.substring(0, 4)}-${compact.substring(4, 6)}-${compact.substring(6, 8)}"

    fun formatStamp(stamp: String): String =
        "${stamp.substring(0, 4)}-${stamp.substring(4, 6)}-${stamp.substring(6, 8)} " +
            "${stamp.substring(8, 10)}:${stamp.substring(10, 12)}:${stamp.substring(12, 14)}"

    fun asciiField(bytes: ByteArray, from: Int, to: Int): String =
        bytes.copyOfRange(from, to.coerceAtMost(bytes.size))
            .joinToString("") { if (it in 0x20..0x7E) it.toInt().toChar().toString() else "" }
}
