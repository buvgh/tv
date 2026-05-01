package com.example.myapplicationlibretv.data.api

import android.text.Html

object ActorFilmographyScraper {
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

    suspend fun fetchKnownTitles(personName: String): List<String> {
        val normalizedName = personName.trim()
        if (normalizedName.isBlank()) return emptyList()

        val localTitles = LocalActorTitleIndex.getTitles(normalizedName)
        if (localTitles.isNotEmpty()) {
            return localTitles
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
                if (titles.isNotEmpty()) return titles
            }
        }

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
            "節目主持", "节目主持", "短片", "參見", "参见", "香港", "中國", "中国"
        )
        if (blacklist.any { title == it }) return false
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
        return java.net.URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "_")
    }
}
