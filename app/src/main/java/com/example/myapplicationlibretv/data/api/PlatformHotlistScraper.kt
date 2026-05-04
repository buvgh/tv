package com.example.myapplicationlibretv.data.api

import android.text.Html
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

object PlatformHotlistScraper {
    private const val CACHE_TTL_MS = 30 * 60 * 1000L
    private const val HOT_TITLE_LIMIT = 160
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    private var cachedAt: Long = 0L
    private var cachedTitles: List<String> = emptyList()
    private val seenPageCache = ConcurrentHashMap<String, String>()

    suspend fun fetchHotTitles(): List<String> {
        val now = System.currentTimeMillis()
        if (cachedTitles.isNotEmpty() && now - cachedAt < CACHE_TTL_MS) {
            return cachedTitles
        }

        val buckets = coroutineScope {
            val doubanJob = async { fetchDoubanTitles() }
            val rottenTomatoesJob = async { fetchOverseasTitles { fetchRottenTomatoesTitles() } }
            val imdbJob = async { fetchOverseasTitles { fetchImdbTitles() } }
            val flixPatrolJob = async { fetchOverseasTitles { fetchFlixPatrolTitles() } }

            HotBuckets(
                douban = doubanJob.await(),
                rottenTomatoes = rottenTomatoesJob.await(),
                imdb = imdbJob.await(),
                flixPatrol = flixPatrolJob.await()
            )
        }

        val titles = (
            currentSeasonTitles +
                interleaveLists(
                    buckets.douban,
                    buckets.flixPatrol,
                    buckets.imdb,
                    buckets.rottenTomatoes
                ) +
                recentFallbackTitles
            )
            .asSequence()
            .map(::cleanTitle)
            .filter(::isLikelyPlayableTitle)
            .distinctBy { normalizeTitleKey(it) }
            .take(HOT_TITLE_LIMIT)
            .toList()

        cachedTitles = titles
        cachedAt = now
        return titles
    }

    private suspend fun fetchOverseasTitles(block: suspend () -> List<String>): List<String> {
        return withTimeoutOrNull(1_200L) {
            block()
        }.orEmpty()
    }

    private suspend fun fetchDoubanTitles(): List<String> = coroutineScope {
        val freshJobs = listOf(
            async { extractChineseTitlesFromHtml(fetchText("https://movie.douban.com/cinema/nowplaying/")) },
            async { extractChineseTitlesFromHtml(fetchText("https://movie.douban.com/chart")) },
            async { fetchMaoyanTitles() }
        )
        val movieJobs = doubanMovieTags.map { tag ->
            async { fetchDoubanHotTitles("movie", tag, if (tag == "最新") "time" else "recommend") }
        }
        val tvJobs = doubanTvTags.map { tag ->
            async { fetchDoubanHotTitles("tv", tag, if (tag == "最新") "time" else "recommend") }
        }
        (
            freshJobs.awaitAll().flatten() +
                interleaveLists(tvJobs.awaitAll().flatten(), movieJobs.awaitAll().flatten())
            )
            .distinctBy(::normalizeTitleKey)
            .take(70)
    }

    private suspend fun fetchDoubanHotTitles(type: String, tag: String, sort: String): List<String> {
        val url = "https://movie.douban.com/j/search_subjects" +
            "?type=$type&tag=${urlEncode(tag)}&sort=$sort&page_limit=30&page_start=0"
        val text = fetchText(url)
        if (text.isBlank()) return emptyList()
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return emptyList()
        val subjects = root["subjects"] as? JsonArray ?: return emptyList()
        return subjects.mapNotNull { item ->
            (item as? JsonObject)
                ?.get("title")
                ?.jsonPrimitive
                ?.contentOrNull
        }
    }

    private suspend fun fetchMaoyanTitles(): List<String> {
        val pages = listOf(
            "https://piaofang.maoyan.com/dashboard",
            "https://piaofang.maoyan.com/rankings/month",
            "https://www.maoyan.com/board/1"
        )
        return pages.flatMap { url ->
            extractChineseTitlesFromHtml(fetchText(url))
        }
    }

    private suspend fun fetchRottenTomatoesTitles(): List<String> {
        val pages = listOf(
            "https://editorial.rottentomatoes.com/guide/popular-movies/",
            "https://editorial.rottentomatoes.com/guide/popular-tv-shows/",
            "https://www.rottentomatoes.com/browse/movies_at_home/sort:popular",
            "https://www.rottentomatoes.com/browse/tv_series_browse/sort:popular"
        )
        return pages.flatMap { url ->
            extractEnglishTitlesFromHtml(fetchText(url))
        }
            .distinctBy(::normalizeTitleKey)
            .take(60)
    }

    private suspend fun fetchImdbTitles(): List<String> {
        val pages = listOf(
            "https://www.imdb.com/chart/moviemeter/",
            "https://www.imdb.com/chart/tvmeter/",
            "https://www.imdb.com/chart/boxoffice/"
        )
        return pages.flatMap { url ->
            extractImdbTitles(fetchText(url))
        }
            .distinctBy(::normalizeTitleKey)
            .take(70)
    }

    private suspend fun fetchFlixPatrolTitles(): List<String> {
        val pages = listOf(
            "https://flixpatrol.com/",
            "https://flixpatrol.com/top10/netflix/world/",
            "https://flixpatrol.com/top10/disney/world/",
            "https://flixpatrol.com/top10/hbo/world/",
            "https://flixpatrol.com/top10/amazon-prime/world/"
        )
        return pages.flatMap { url ->
            extractEnglishTitlesFromHtml(fetchText(url))
        }
            .distinctBy(::normalizeTitleKey)
            .take(80)
    }

    private suspend fun fetchText(url: String): String {
        seenPageCache[url]?.let { return it }
        val text = runCatching {
            RetrofitClient.cmsApi.getRaw(url).string()
        }.getOrNull().orEmpty()
        if (text.isNotBlank()) {
            seenPageCache[url] = text
        }
        return text
    }

    private fun extractChineseTitlesFromHtml(html: String): List<String> {
        if (html.isBlank()) return emptyList()
        val decoded = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString()
        val candidates = mutableListOf<String>()
        Regex("""《([^《》]{2,24})》""").findAll(decoded).forEach { match ->
            candidates += match.groupValues[1]
        }
        Regex(""""(?:movieName|name|title|filmName|titleCn|nm)"\s*:\s*"([^"\\]{2,36})"""").findAll(html).forEach { match ->
            candidates += match.groupValues[1]
        }
        Regex("""data-title="([^"]{2,36})"""").findAll(html).forEach { match ->
            candidates += match.groupValues[1]
        }
        return candidates
    }

    private fun extractImdbTitles(html: String): List<String> {
        if (html.isBlank()) return emptyList()
        val candidates = mutableListOf<String>()
        Regex(""""titleText"\s*:\s*\{\s*"text"\s*:\s*"([^"]{2,80})"""").findAll(html).forEach { match ->
            candidates += match.groupValues[1]
        }
        Regex(""""primaryTitle"\s*:\s*"([^"]{2,80})"""").findAll(html).forEach { match ->
            candidates += match.groupValues[1]
        }
        Regex("""<h3[^>]*>\s*\d+\.\s*([^<]{2,80})</h3>""").findAll(html).forEach { match ->
            candidates += match.groupValues[1]
        }
        return candidates
    }

    private fun extractEnglishTitlesFromHtml(html: String): List<String> {
        if (html.isBlank()) return emptyList()
        val decoded = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString()
        val candidates = mutableListOf<String>()
        Regex("""<h2[^>]*>([^<]{2,80})</h2>""").findAll(html).forEach { match ->
            candidates += match.groupValues[1]
        }
        Regex("""<h3[^>]*>(?:\s*\d+\.\s*)?([^<]{2,80})</h3>""").findAll(html).forEach { match ->
            candidates += match.groupValues[1]
        }
        Regex("""(?:data-title|title|alt)="([^"]{2,80})"""").findAll(html).forEach { match ->
            candidates += match.groupValues[1]
        }
        Regex(""""(?:name|title|movieName|showName)"\s*:\s*"([^"\\]{2,80})"""").findAll(html).forEach { match ->
            candidates += match.groupValues[1]
        }
        decoded.lines().forEach { line ->
            val value = line.trim()
            if (value.length in 2..80 && value.count { it.isLetter() } >= 2) {
                candidates += value
            }
        }
        return candidates
    }

    private fun cleanTitle(raw: String): String {
        return Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY)
            .toString()
            .replace(Regex("\\s+"), " ")
            .replace(Regex("""^\d+\.\s*"""), "")
            .replace(Regex(""":\s*(Season|Limited Series|Miniseries)\s*\d*.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*-\s*[A-Za-z]+\s+\d{1,2},\s+\d{4}$"""), "")
            .replace(Regex("""\s*\(\d{4}\)\s*$"""), "")
            .replace("《", "")
            .replace("》", "")
            .trim()
    }

    private fun isLikelyPlayableTitle(title: String): Boolean {
        if (title.length !in 2..40) return false
        if (!title.any { it.isLetterOrDigit() || Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }) {
            return false
        }
        val noise = listOf(
            "豆瓣", "猫眼", "票房", "电影票", "购票", "排行榜", "热映", "影院", "上映",
            "总票房", "实时", "暂无", "更多", "首页", "登录", "注册", "预告片",
            "Netflix", "Top 10", "Tudum", "Logo", "Watch", "My List", "IMDb", "Rotten Tomatoes",
            "FlixPatrol", "Official Trailer", "Poster", "Watchlist", "Critics Consensus",
            "Image", "Icon", "Menu", "Movies", "TV Shows", "Streaming", "Popular"
        )
        return noise.none { title.contains(it, ignoreCase = true) }
    }

    private fun interleaveLists(vararg lists: List<String>): List<String> {
        val result = mutableListOf<String>()
        val maxSize = lists.maxOfOrNull { it.size } ?: 0
        for (index in 0 until maxSize) {
            lists.forEach { list ->
                list.getOrNull(index)?.let(result::add)
            }
        }
        return result
    }

    private fun normalizeTitleKey(value: String): String {
        return value.lowercase()
            .replace(Regex("""[^\p{IsHan}a-z0-9]"""), "")
            .replace("第一季", "")
            .replace("第二季", "")
            .replace("第三季", "")
            .replace("第1季", "")
            .replace("第2季", "")
            .replace("第3季", "")
    }

    private fun urlEncode(value: String): String {
        return URLEncoder.encode(value, Charsets.UTF_8.name())
    }

    private val kotlinx.serialization.json.JsonPrimitive.contentOrNull: String?
        get() = runCatching { content }.getOrNull()

    private data class HotBuckets(
        val douban: List<String>,
        val rottenTomatoes: List<String>,
        val imdb: List<String>,
        val flixPatrol: List<String>
    )

    private val doubanMovieTags = listOf(
        "最新", "热门", "华语", "欧美", "韩国", "日本", "动作", "喜剧", "爱情", "科幻", "悬疑"
    )

    private val doubanTvTags = listOf(
        "最新", "热门", "国产剧", "美剧", "英剧", "韩剧", "日剧", "港剧", "日本动画", "综艺", "纪录片"
    )

    private val currentSeasonTitles = listOf(
        "追恶", "旅途中的日子", "生命树", "太平年", "危险关系", "骄阳似我", "九重天", "中国奇谭2",
        "白日提灯", "影子姐妹", "水龙吟", "书卷一梦", "烽影燃梅香", "北上", "蛮好的人生", "无忧渡",
        "折腰", "藏海传", "淮水竹亭", "棋士", "雁回时", "乌云之上", "似锦", "成家", "六姊妹",
        "难哄", "滤镜", "国色芳华", "漂白", "了不起的曹萱萱", "夺娶", "锦囊妙录", "五福临门"
    )

    private val recentFallbackTitles = listOf(
        "九龙城寨之围城", "破墓", "周处除三害", "首尔之春", "年会不能停", "热辣滚烫", "飞驰人生2",
        "第二十条", "沙丘2", "哥斯拉大战金刚2", "蜘蛛侠纵横宇宙", "银河护卫队3", "疾速追杀4",
        "奥本海默", "芭比", "毒舌律师", "临时劫案", "金手指", "潜行", "爆裂点", "海关战线",
        "繁花", "狂飙", "漫长的季节", "三体", "莲花楼", "长相思", "我的阿勒泰", "南来北往",
        "追风者", "承欢记", "与凤行", "玫瑰的故事", "墨雨云间", "新生", "哈尔滨一九四四", "城中之城"
    )
}
