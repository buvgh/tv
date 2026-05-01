package com.example.myapplicationlibretv.data.api

import android.net.Uri
import com.example.myapplicationlibretv.data.model.CmsResponse
import com.example.myapplicationlibretv.data.model.VideoItem
import kotlinx.serialization.json.Json
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

private val cmsJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

suspend fun fetchCmsResponse(
    baseUrl: String,
    action: String? = null,
    typeId: Int? = null,
    page: Int = 1,
    keyword: String? = null,
    ids: String? = null
): CmsResponse {
    val requestUrl = buildCmsRequestUrl(
        baseUrl = baseUrl,
        action = action,
        typeId = typeId,
        page = page,
        keyword = keyword,
        ids = ids
    )
    val text = RetrofitClient.cmsApi.getRaw(requestUrl).string()
    return parseCmsResponse(text)
}

fun buildCmsRequestUrl(
    baseUrl: String,
    action: String? = null,
    typeId: Int? = null,
    page: Int = 1,
    keyword: String? = null,
    ids: String? = null
): String {
    val normalizedBase = baseUrl.trim()
    val uri = Uri.parse(normalizedBase)
    val builder = uri.buildUpon().clearQuery()

    uri.queryParameterNames.forEach { key ->
        uri.getQueryParameters(key).forEach { value ->
            builder.appendQueryParameter(key, value)
        }
    }

    val resolvedAction = action ?: if (!keyword.isNullOrBlank() || !ids.isNullOrBlank()) {
        "detail"
    } else {
        "videolist"
    }

    if (uri.getQueryParameter("ac").isNullOrBlank()) {
        builder.appendQueryParameter("ac", resolvedAction)
    }
    if (uri.getQueryParameter("pg").isNullOrBlank()) {
        builder.appendQueryParameter("pg", page.toString())
    }
    if (typeId != null && uri.getQueryParameter("t").isNullOrBlank()) {
        builder.appendQueryParameter("t", typeId.toString())
    }
    if (!keyword.isNullOrBlank()) {
        builder.appendQueryParameter("wd", keyword)
    }
    if (!ids.isNullOrBlank()) {
        builder.appendQueryParameter("ids", ids)
    }
    return builder.build().toString()
}

fun parseCmsResponse(text: String): CmsResponse {
    val trimmed = text.trim()
    if (trimmed.isBlank()) return CmsResponse()
    return if (trimmed.startsWith("<") || trimmed.contains("<rss", ignoreCase = true)) {
        parseXmlCmsResponse(trimmed)
    } else {
        // 针对大规模 JSON 数据（如 9.5 万条目）的解析优化：
        // kotlinx.serialization 在处理超大 String 时可能触发 OOM，
        // 这里依赖于 OkHttp 的 ResponseBody.string() 已经拉取到内存。
        // 为保证鲁棒性，使用 lenient 模式并忽略未知字段。
        runCatching {
            cmsJson.decodeFromString(CmsResponse.serializer(), trimmed)
        }.getOrElse { e ->
            android.util.Log.e("CmsContentParser", "JSON parse error", e)
            CmsResponse()
        }
    }
}

private fun parseXmlCmsResponse(xml: String): CmsResponse {
    val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
        setInput(StringReader(xml))
    }

    val videos = mutableListOf<VideoItem>()
    var currentTag: String? = null
    var inVideo = false

    var id = 0
    var name = ""
    var typeId: Int? = null
    var typeName: String? = null
    var enName: String? = null
    var actor: String? = null
    var director: String? = null
    var area: String? = null
    var lang: String? = null
    var year: String? = null
    var time: String? = null
    var remarks: String? = null
    var playFrom: String? = null
    var playUrl: String? = null
    var pic: String? = null
    var content: String? = null

    fun resetVideo() {
        id = 0
        name = ""
        typeId = null
        typeName = null
        enName = null
        actor = null
        director = null
        area = null
        lang = null
        year = null
        time = null
        remarks = null
        playFrom = null
        playUrl = null
        pic = null
        content = null
    }

    resetVideo()

    while (parser.eventType != XmlPullParser.END_DOCUMENT) {
        when (parser.eventType) {
            XmlPullParser.START_TAG -> {
                currentTag = parser.name
                if (parser.name.equals("video", ignoreCase = true) || parser.name.equals("item", ignoreCase = true)) {
                    inVideo = true
                    resetVideo()
                }
            }

            XmlPullParser.TEXT -> {
                if (!inVideo) {
                    parser.next()
                    continue
                }
                val value = parser.text?.trim().orEmpty()
                if (value.isBlank()) {
                    parser.next()
                    continue
                }
                when (currentTag?.lowercase()) {
                    "id", "vod_id" -> id = value.toIntOrNull() ?: id
                    "name", "vod_name", "title" -> name = value
                    "tid", "type_id" -> typeId = value.toIntOrNull()
                    "type", "type_name" -> typeName = value
                    "vod_en", "ename" -> enName = value
                    "vod_actor", "actor", "actors" -> actor = value
                    "vod_director", "director" -> director = value
                    "vod_area", "area" -> area = value
                    "vod_lang", "lang", "language" -> lang = value
                    "vod_year", "year" -> year = value
                    "last", "vod_time", "pubdate" -> time = value
                    "note", "vod_remarks", "remarks" -> remarks = value
                    "dt", "vod_play_from" -> playFrom = value
                    "dl", "vod_play_url" -> {
                        // 兼容 MoonTV 格式：如果包含 $ 和 # 则直接使用，否则可能需要 Base64 解码 (此处保持原始逻辑，在 DetailViewModel 解析)
                        playUrl = value
                    }
                    "pic", "vod_pic", "cover" -> pic = value
                    "des", "vod_content", "content" -> content = value
                }
            }

            XmlPullParser.END_TAG -> {
                if (parser.name.equals("video", ignoreCase = true) || parser.name.equals("item", ignoreCase = true)) {
                    if (name.isNotBlank()) {
                        videos += VideoItem(
                            id = if (id != 0) id else name.hashCode(),
                            name = name,
                            typeId = typeId,
                            typeName = typeName,
                            enName = enName,
                            actor = actor,
                            director = director,
                            area = area,
                            lang = lang,
                            year = year,
                            time = time,
                            remarks = remarks,
                            playFrom = playFrom,
                            playUrl = playUrl,
                            pic = pic,
                            content = content
                        )
                    }
                    inVideo = false
                    currentTag = null
                }
            }
        }
        parser.next()
    }

    return CmsResponse(list = videos)
}
