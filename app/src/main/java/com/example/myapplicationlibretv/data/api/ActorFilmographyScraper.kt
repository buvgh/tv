package com.example.myapplicationlibretv.data.api

import android.text.Html
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

object ActorFilmographyScraper {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    private val titleCache = ConcurrentHashMap<String, List<String>>()
    private val wikiBases = listOf(
        "https://zh.wikipedia.org",
        "https://zh.m.wikipedia.org"
    )
    private val traditionalToSimplified = mapOf(
        '馳' to '驰', '賭' to '赌', '聖' to '圣', '俠' to '侠', '龍' to '龙',
        '風' to '风', '國' to '国', '無' to '无', '寶' to '宝', '長' to '长',
        '麗' to '丽', '東' to '东', '餘' to '余', '麵' to '面', '點' to '点',
        '畫' to '画', '壞' to '坏', '滅' to '灭', '愛' to '爱', '來' to '来',
        '與' to '与', '開' to '开', '門' to '门', '黃' to '黄', '媽' to '妈',
        '歲' to '岁', '驚' to '惊', '記' to '记', '劍' to '剑', '飛' to '飞',
        '術' to '术', '貓' to '猫', '頭' to '头', '燈' to '灯', '馬' to '马',
        '爺' to '爷', '師' to '师', '鄉' to '乡', '將' to '将'
    )

    suspend fun fetchKnownTitles(
        personName: String,
        includeAdultWebSearch: Boolean = false
    ): List<String> {
        val normalizedName = personName.trim()
        if (normalizedName.isBlank()) return emptyList()
        val cacheKey = if (includeAdultWebSearch) "$normalizedName#adult" else normalizedName
        titleCache[cacheKey]?.let { return it }

        val localTitles = LocalActorTitleIndex.getTitles(normalizedName)
        if (localTitles.isNotEmpty()) {
            titleCache[cacheKey] = localTitles
            return localTitles
        }

        val baiduTitles = fetchBaiduBaikeTitles(normalizedName)
        val yandexTitles = if (includeAdultWebSearch) {
            fetchYandexAdultTitles(normalizedName)
        } else {
            emptyList()
        }
        val baiduAndYandexTitles = (baiduTitles + yandexTitles).distinct()
        if (baiduAndYandexTitles.isNotEmpty()) {
            titleCache[cacheKey] = baiduAndYandexTitles
            return baiduAndYandexTitles
        }

        val wikidataTitles = fetchWikidataFilmographyTitles(normalizedName)
        if (wikidataTitles.isNotEmpty()) {
            titleCache[cacheKey] = wikidataTitles
            return wikidataTitles
        }

        val candidateNames = buildPersonNameVariants(normalizedName)
        for (wikiBase in wikiBases) {
            for (candidateName in candidateNames) {
                val profileUrl = "$wikiBase/wiki/${encodeWikiTitle(candidateName)}"
                val profileHtml = runCatching {
                    RetrofitClient.cmsApi.getRaw(profileUrl).string()
                }.getOrNull().orEmpty()
                if (profileHtml.isBlank()) continue

                val filmographyPath = extractFilmographyPath(profileHtml)
                    ?: "/wiki/${encodeWikiTitle("${candidateName}影視作品列表")}"
                val filmographyHtml = runCatching {
                    RetrofitClient.cmsApi.getRaw("$wikiBase$filmographyPath").string()
                }.getOrNull().orEmpty()
                if (filmographyHtml.isBlank()) continue

                val titles = extractTitlesFromFilmography(filmographyHtml)
                if (titles.isNotEmpty()) {
                    titleCache[cacheKey] = titles
                    return titles
                }
            }
        }

        titleCache[cacheKey] = emptyList()
        return emptyList()
    }

    fun expandTitleAliases(titles: List<String>): List<String> {
        return titles.asSequence()
            .flatMap { expandTitleAliases(it).asSequence() }
            .distinct()
            .toList()
    }

    private fun extractFilmographyPath(profileHtml: String): String? {
        val pattern = Regex("""href="(/wiki/[^"#?]+影視作品列表)"[^>]*title="[^"]*影視作品列表"""")
        return pattern.find(profileHtml)?.groupValues?.getOrNull(1)
    }

    private suspend fun fetchBaiduBaikeTitles(personName: String): List<String> {
        val pageUrl = "https://baike.baidu.com/item/${urlEncode(personName)}"
        val html = runCatching {
            RetrofitClient.cmsApi.getRaw(pageUrl).string()
        }.getOrNull().orEmpty()
        if (html.isBlank()) return emptyList()

        val workSection = sliceBaiduWorkSection(html)
        val candidates = mutableListOf<String>()
        Regex("""《([^》]{2,30})》""").findAll(workSection).forEach { match ->
            candidates += match.groupValues[1]
        }
        Regex(""""(?:title|name|lemmaTitle|text)":"([^"\\]{2,30})"""").findAll(workSection).forEach { match ->
            candidates += match.groupValues[1]
        }
        return candidates
            .asSequence()
            .map(::sanitizeTitle)
            .map(::toSimplified)
            .filter(::isLikelyWorkTitle)
            .filterNot(::isLikelyBaiduNoiseTitle)
            .distinct()
            .take(90)
            .toList()
    }

    private fun sliceBaiduWorkSection(html: String): String {
        val startMarkers = listOf("主要作品", "参演电影", "参演电视剧", "导演作品", "影视作品", "作品")
        val endMarkers = listOf("获奖记录", "社会活动", "个人生活", "人物评价", "争议事件", "参考资料", "词条图册")
        val start = startMarkers
            .map { html.indexOf(it) }
            .filter { it >= 0 }
            .minOrNull()
            ?: return html.take(240_000)
        val end = endMarkers
            .map { html.indexOf(it, start + 20) }
            .filter { it > start }
            .minOrNull()
            ?: (start + 260_000).coerceAtMost(html.length)
        return html.substring(start, end.coerceAtMost(html.length))
    }

    private fun isLikelyBaiduNoiseTitle(title: String): Boolean {
        val compact = title.replace(Regex("\\s+"), "")
        val noiseKeywords = listOf(
            "定档", "上映", "开机", "票房", "提名", "获奖", "揭晓", "名单", "剧照", "海报",
            "预告", "花絮", "发布会", "主持阵容", "节目单", "春节联欢晚会", "元宵节",
            "纪录片", "全部演职员", "不知道的幕后", "主题曲", "片尾曲", "片头曲", "插曲",
            "新闻", "专访", "组图", "官宣", "撤档", "播出", "收视", "庆功"
        )
        if (noiseKeywords.any { compact.contains(it) }) return true
        if (compact.startsWith("#") || compact.endsWith("#")) return true
        if (compact.contains("：") && compact.length > 16) return true
        return false
    }

    private suspend fun fetchYandexAdultTitles(personName: String): List<String> {
        val queries = listOf(
            "$personName 作品",
            "$personName filmography",
            "$personName javdb",
            "$personName movies"
        )
        val candidates = mutableListOf<String>()
        queries.forEach { query ->
            val url = "https://yandex.com/search/?text=${urlEncode(query)}"
            val html = runCatching { RetrofitClient.cmsApi.getRaw(url).string() }
                .getOrNull()
                .orEmpty()
            if (!html.isYandexVerificationPage()) {
                candidates += extractTitlesFromYandexHtml(html)
            }
        }
        return candidates
            .asSequence()
            .map(::sanitizeTitle)
            .filter(::isLikelyYandexAdultTitle)
            .distinct()
            .take(80)
            .toList()
    }

    private fun extractTitlesFromYandexHtml(html: String): List<String> {
        val decoded = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString()
        val candidates = mutableListOf<String>()
        Regex("""《([^》]{2,40})》""").findAll(decoded).forEach { match ->
            candidates += match.groupValues[1]
        }
        Regex("""\b[A-Z]{2,8}[-_ ]?\d{2,6}\b""").findAll(decoded).forEach { match ->
            candidates += match.value.replace(" ", "-").replace("_", "-")
        }
        Regex("""(?:title|aria-label)="([^"]{2,60})"""").findAll(html).forEach { match ->
            candidates += match.groupValues[1]
        }
        return candidates
    }

    private fun String.isYandexVerificationPage(): Boolean {
        return contains("Verification", ignoreCase = true) ||
            contains("captcha", ignoreCase = true) ||
            contains("smartcaptcha", ignoreCase = true)
    }

    private fun isLikelyYandexAdultTitle(title: String): Boolean {
        val cleaned = title.trim()
        if (cleaned.length !in 3..40) return false
        if (cleaned.contains("Yandex", ignoreCase = true)) return false
        if (cleaned.contains("Verification", ignoreCase = true)) return false
        if (cleaned.matches(Regex("""[A-Z]{2,8}[-_ ]?\d{2,6}"""))) return true
        return isLikelyWorkTitle(cleaned) && !isLikelyBaiduNoiseTitle(cleaned)
    }

    private suspend fun fetchWikidataFilmographyTitles(personName: String): List<String> {
        val personIds = searchWikidataPersonIds(personName)
        if (personIds.isEmpty()) return emptyList()

        val titles = mutableListOf<String>()
        personIds.forEach { entityId ->
            titles += fetchWikidataTitlesForPerson(entityId)
        }
        return titles.asSequence()
            .map(::sanitizeTitle)
            .map(::toSimplified)
            .filter(::isLikelyWorkTitle)
            .distinct()
            .take(90)
            .toList()
    }

    private suspend fun searchWikidataPersonIds(personName: String): List<String> {
        val encoded = urlEncode(personName)
        val languages = listOf("zh", "en")
        val ids = mutableListOf<String>()
        languages.forEach { language ->
            val url = "https://www.wikidata.org/w/api.php?action=wbsearchentities" +
                "&search=$encoded&language=$language&format=json&limit=5"
            val text = runCatching { RetrofitClient.cmsApi.getRaw(url).string() }
                .getOrNull()
                .orEmpty()
            ids += parseWikidataSearchIds(text)
        }
        return ids.asSequence()
            .distinct()
            .take(3)
            .toList()
    }

    private fun parseWikidataSearchIds(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return emptyList()
        val search = root["search"] as? JsonArray ?: return emptyList()
        return search.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            obj["id"]?.jsonPrimitive?.contentOrNull
        }
    }

    private suspend fun fetchWikidataTitlesForPerson(entityId: String): List<String> {
        val query = """
            SELECT ?work ?workLabel WHERE {
              { ?work wdt:P161 wd:$entityId. }
              UNION { ?work wdt:P57 wd:$entityId. }
              UNION { ?work wdt:P58 wd:$entityId. }
              ?work wdt:P31/wdt:P279* ?type.
              VALUES ?type { wd:Q11424 wd:Q5398426 wd:Q24862 wd:Q15416 }
              SERVICE wikibase:label { bd:serviceParam wikibase:language "zh,en". }
            }
            LIMIT 120
        """.trimIndent()
        val url = "https://query.wikidata.org/sparql?format=json&query=${urlEncode(query)}"
        val text = runCatching { RetrofitClient.cmsApi.getRaw(url).string() }
            .getOrNull()
            .orEmpty()
        return parseWikidataTitleResults(text)
    }

    private fun parseWikidataTitleResults(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return emptyList()
        val bindings = root["results"]
            ?.jsonObjectOrNull()
            ?.get("bindings")
            as? JsonArray ?: return emptyList()
        return bindings.mapNotNull { item ->
            item.jsonObjectOrNull()
                ?.get("workLabel")
                ?.jsonObjectOrNull()
                ?.get("value")
                ?.jsonPrimitive
                ?.contentOrNull
        }
    }

    private fun extractTitlesFromFilmography(html: String): List<String> {
        val content = html.substringAfter("""<h2 id="電影"""", html)
        val truncated = content.substringBefore("""<h2 id="參見"""", content)
        val anchorPattern = Regex("""<a [^>]*title="([^"]+)"[^>]*>(.*?)</a>""")

        return anchorPattern.findAll(truncated)
            .mapNotNull { match ->
                val titleAttr = sanitizeTitle(match.groupValues[1])
                val text = sanitizeTitle(match.groupValues[2])
                listOf(titleAttr, text)
                    .firstOrNull { isLikelyWorkTitle(it) }
            }
            .distinct()
            .take(80)
            .toList()
    }

    private fun expandTitleAliases(title: String): List<String> {
        val cleaned = sanitizeTitle(title)
        if (!isLikelyWorkTitle(cleaned)) return emptyList()

        val baseForms = listOf(
            cleaned,
            cleaned.replace(" ", ""),
            cleaned.replace(Regex("""\s*第\s*\d+\s*[季部集篇]\s*"""), " "),
            cleaned.replace(Regex("""\b\d{4}\b"""), " "),
            cleaned.replace(Regex("""[：:·•／/·,，]+"""), " "),
            cleaned.replace(Regex("""\s+"""), " ")
        ).map { it.trim() }
            .filter { it.length >= 2 }

        return baseForms
            .flatMap { form ->
                val simplified = toSimplified(form)
                val normalizedSeries = simplified
                    .replace("电影", "")
                    .replace("電影", "")
                    .replace("劇場版", "")
                    .replace("电视剧", "")
                    .replace("電視劇", "")
                    .trim()
                listOf(
                    form,
                    simplified,
                    normalizedSeries,
                    normalizedSeries.replace(Regex("""\s+"""), "")
                )
            }
            .map { it.trim() }
            .filter { it.length >= 2 && isLikelyWorkTitle(it) }
            .distinct()
            .take(6)
    }

    private fun sanitizeTitle(raw: String): String {
        return Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY)
            .toString()
            .replace(Regex("\\s+"), " ")
            .replace(Regex("""\s*\([^)]*\)\s*"""), " ")
            .replace(Regex("""\s*（[^）]*）\s*"""), " ")
            .replace("《", "")
            .replace("》", "")
            .trim()
    }

    private fun isLikelyWorkTitle(title: String): Boolean {
        if (title.length !in 2..30) return false
        if (title.matches(Regex("""\d{4}"""))) return false
        val blacklist = listOf(
            "周星馳", "周星驰", "影視作品", "影视作品", "作品列表", "電影", "电影", "劇集", "剧集",
            "節目主持", "节目主持", "短片", "參見", "参见", "香港", "中國", "中国",
            "電影宇宙", "电影宇宙", "奧林匹克", "奥林匹克"
        )
        if (blacklist.any { title == it || title.contains(it) }) return false
        if (title.endsWith("列表")) return false
        if (title.contains("年")) return false
        return title.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN } ||
            title.any { it.isLetterOrDigit() }
    }

    private fun buildPersonNameVariants(personName: String): List<String> {
        val simplified = toSimplified(personName)
        val traditional = toTraditionalApprox(personName)
        return listOf(personName, simplified, traditional)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun toSimplified(text: String): String {
        return buildString(text.length) {
            text.forEach { append(traditionalToSimplified[it] ?: it) }
        }
    }

    private fun toTraditionalApprox(text: String): String {
        val reverseMap = traditionalToSimplified.entries.associate { (k, v) -> v to k }
        return buildString(text.length) {
            text.forEach { append(reverseMap[it] ?: it) }
        }
    }

    private fun encodeWikiTitle(value: String): String {
        return urlEncode(value).replace("+", "_")
    }

    private fun urlEncode(value: String): String {
        return URLEncoder.encode(value, Charsets.UTF_8.name())
    }

    private fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject

    private val kotlinx.serialization.json.JsonPrimitive.contentOrNull: String?
        get() = runCatching { content }.getOrNull()
}
