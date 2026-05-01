package com.example.myapplicationlibretv.data.repository

import android.util.Base64
import com.example.myapplicationlibretv.data.api.RetrofitClient
import com.example.myapplicationlibretv.data.model.Site
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigInteger
import java.nio.charset.StandardCharsets

object SourceRepository {
    data class SubscriptionLoadResult(
        val requestedUrls: Int,
        val parsedSites: Int,
        val failedUrls: Int,
        val mergedSites: List<Site>,
        val selectedSite: Site?
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // 内置一批 MoonTV 常用 CMS 源，手机即使无法访问 GitHub Raw 也能直接使用
    private val defaultSites = listOf(
        Site(key = "builtin_lzi", name = "量子资源", api = "https://cj.lziapi.com/api.php/provide/vod/", type = 1),
        Site(key = "builtin_1080", name = "1080资源", api = "https://api.1080zyku.com/inc/api_mac10.php", type = 1),
        Site(key = "builtin_uku", name = "U酷资源", api = "https://api.ukuapi.com/api.php/provide/vod", type = 1),
        Site(key = "builtin_uku88", name = "U酷资源2", api = "https://api.ukuapi88.com/api.php/provide/vod", type = 1),
        Site(key = "builtin_ikun", name = "iKun资源", api = "https://ikunzyapi.com/api.php/provide/vod", type = 1),
        Site(key = "builtin_wujin_cc", name = "无尽资源CC", api = "https://api.wujinapi.cc/api.php/provide/vod", type = 1),
        Site(key = "builtin_wujin_com", name = "无尽资源", api = "https://api.wujinapi.com/api.php/provide/vod", type = 1),
        Site(key = "builtin_wujin_me", name = "无尽资源ME", api = "https://api.wujinapi.me/api.php/provide/vod", type = 1),
        Site(key = "builtin_wujin_net", name = "无尽资源NET", api = "https://api.wujinapi.net/api.php/provide/vod", type = 1),
        Site(key = "builtin_yaya", name = "丫丫点播", api = "https://cj.yayazy.net/api.php/provide/vod", type = 1),
        Site(key = "builtin_guangsu", name = "光速资源", api = "https://api.guangsuapi.com/api.php/provide/vod", type = 1),
        Site(key = "builtin_wolong_collect", name = "卧龙点播", api = "https://collect.wolongzyw.com/api.php/provide/vod", type = 1),
        Site(key = "builtin_wolong", name = "卧龙资源", api = "https://wolongzyw.com/api.php/provide/vod", type = 1),
        Site(key = "builtin_ry", name = "如意资源", api = "https://cj.rycjapi.com/api.php/provide/vod", type = 1),
        Site(key = "builtin_xm", name = "小猫咪资源", api = "https://zy.xmm.hk/api.php/provide/vod", type = 1),
        Site(key = "builtin_xinlang", name = "新浪点播", api = "https://api.xinlangapi.com/xinlangapi.php/provide/vod", type = 1),
        Site(key = "builtin_ww", name = "旺旺资源", api = "https://api.wwzy.tv/api.php/provide/vod", type = 1),
        Site(key = "builtin_bf", name = "暴风资源", api = "https://bfzyapi.com/api.php/provide/vod", type = 1),
        Site(key = "builtin_zuida", name = "最大资源", api = "https://api.zuidapi.com/api.php/provide/vod", type = 1),
        Site(key = "builtin_yh", name = "樱花资源", api = "https://m3u8.apiyhzy.com/api.php/provide/vod", type = 1),
        Site(key = "builtin_niuniu", name = "牛牛点播", api = "https://api.niuniuzy.me/api.php/provide/vod", type = 1),
        Site(key = "builtin_apibd", name = "百度云资源", api = "https://api.apibdzy.com/api.php/provide/vod", type = 1),
        Site(key = "builtin_suoni", name = "索尼资源", api = "https://suoniapi.com/api.php/provide/vod", type = 1),
        Site(key = "builtin_hongniu", name = "红牛资源", api = "https://www.hongniuzy2.com/api.php/provide/vod", type = 1),
        Site(key = "builtin_maotai", name = "茅台资源", api = "https://caiji.maotaizy.cc/api.php/provide/vod", type = 1),
        Site(key = "builtin_huya", name = "虎牙资源", api = "https://www.huyaapi.com/api.php/provide/vod", type = 1),
        Site(key = "builtin_db_caiji", name = "豆瓣资源", api = "https://caiji.dbzy.tv/api.php/provide/vod", type = 1),
        Site(key = "builtin_db", name = "豆瓣资源2", api = "https://dbzy.tv/api.php/provide/vod", type = 1),
        Site(key = "builtin_hh", name = "豪华资源", api = "https://hhzyapi.com/api.php/provide/vod", type = 1),
        Site(key = "builtin_subo", name = "速播资源", api = "https://subocaiji.com/api.php/provide/vod", type = 1),
        Site(key = "builtin_jy", name = "金鹰资源", api = "https://jyzyapi.com/api.php/provide/vod", type = 1),
        Site(key = "builtin_sd", name = "闪电资源", api = "https://sdzyapi.com/api.php/provide/vod", type = 1),
        Site(key = "builtin_ff", name = "非凡资源", api = "https://cj.ffzyapi.com/api.php/provide/vod", type = 1),
        Site(key = "builtin_p2100", name = "飘零资源", api = "https://p2100.net/api.php/provide/vod", type = 1),
        Site(key = "builtin_mozhua", name = "魔爪资源", api = "https://mozhuazy.com/api.php/provide/vod", type = 1),
        Site(key = "builtin_modu", name = "魔都资源", api = "https://www.mdzyapi.com/api.php/provide/vod", type = 1),
        Site(key = "builtin_heimuer", name = "黑木耳", api = "https://json.heimuer.xyz/api.php/provide/vod", type = 1),
        Site(key = "builtin_heimuer2", name = "黑木耳点播", api = "https://json02.heimuer.xyz/api.php/provide/vod", type = 1)
    )

    private var allSites: List<Site> = defaultSites
    private var adultContentEnabled: Boolean = false

    private val _sites = MutableStateFlow<List<Site>>(filterAdultSites(defaultSites))
    val sites: StateFlow<List<Site>> = _sites

    private val _currentSite = MutableStateFlow<Site?>(_sites.value.firstOrNull())
    val currentSite: StateFlow<Site?> = _currentSite

    fun selectSite(site: Site) {
        _currentSite.value = site
    }

    fun selectSiteByKey(siteKey: String) {
        val site = _sites.value.firstOrNull { it.key == siteKey || it.api == siteKey } ?: return
        _currentSite.value = site
    }

    fun getSitesSnapshot(): List<Site> = _sites.value

    fun getDefaultSites(): List<Site> = defaultSites

    fun setAdultContentEnabled(enabled: Boolean) {
        if (adultContentEnabled == enabled && _sites.value.isNotEmpty()) return
        adultContentEnabled = enabled
        publishVisibleSites(_currentSite.value?.key)
    }

    fun setSites(sites: List<Site>, selectedSiteKey: String? = null) {
        if (sites.isEmpty()) return
        allSites = sites
        publishVisibleSites(selectedSiteKey)
    }

    suspend fun loadSubscription(url: String): SubscriptionLoadResult {
        return withContext(Dispatchers.IO) {
            val urls = url
                .split("\n", ",", ";")
                .map { it.trim() }
                .filter { it.isNotBlank() }

            if (urls.isEmpty()) {
                return@withContext SubscriptionLoadResult(
                    requestedUrls = 0,
                    parsedSites = 0,
                    failedUrls = 0,
                    mergedSites = _sites.value,
                    selectedSite = _currentSite.value
                )
            }

            val collectedSites = mutableListOf<Site>()
            var selected: Site? = null
            var failedUrls = 0

            for (u in urls) {
                val success = runCatching {
                    if (u.contains("api.php") || u.contains("provide/vod")) {
                        val site = Site(
                            key = u.hashCode().toString(),
                            name = "自定义源",
                            api = u,
                            type = 1
                        )
                        collectedSites += site
                        if (selected == null) selected = site
                    } else {
                        val responseBody = RetrofitClient.cmsApi.getRaw(u)
                        val text = responseBody.string()
                        val parsedSites = parseSitesFromTvBoxText(text)

                        val filteredSites = parsedSites.filter {
                            val apiLow = it.api.lowercase()
                            (it.type == 1 || it.type == null) && isSupportedCmsApi(apiLow)
                        }
                        collectedSites += filteredSites
                        if (selected == null && filteredSites.isNotEmpty()) {
                            selected = filteredSites.first()
                        }
                    }
                }.isSuccess
                if (!success) {
                    failedUrls += 1
                }
            }

            val merged = if (collectedSites.isNotEmpty()) {
                (collectedSites + defaultSites)
                    .map { it.copy(api = it.api.trim()) }
                    .distinctBy { it.api }
            } else {
                allSites
            }

            if (collectedSites.isNotEmpty()) {
                allSites = merged
                publishVisibleSites(selected?.key ?: _currentSite.value?.key)
            }

            SubscriptionLoadResult(
                requestedUrls = urls.size,
                parsedSites = collectedSites.size,
                failedUrls = failedUrls,
                mergedSites = filterAdultSites(merged),
                selectedSite = selected ?: _currentSite.value
            )
        }
    }

    private fun publishVisibleSites(selectedSiteKey: String?) {
        val visibleSites = filterAdultSites(allSites)
        _sites.value = visibleSites
        val selected = selectedSiteKey?.let { key ->
            visibleSites.firstOrNull { it.key == key || it.api == key }
        }
        _currentSite.value = selected ?: visibleSites.firstOrNull()
    }

    private fun filterAdultSites(sites: List<Site>): List<Site> {
        if (adultContentEnabled) return sites
        return sites.filterNot(::isAdultSite)
    }

    private fun isAdultSite(site: Site): Boolean {
        val raw = "${site.name} ${site.key.orEmpty()} ${site.api}".lowercase()
        val compact = raw.replace(Regex("\\s+"), "")
        val strongKeywords = listOf(
            "伦理", "伦理片", "情色", "成人", "av", "porn", "hentai", "福利", "写真",
            "swag", "麻豆", "91", "私房", "两性", "无码", "有码", "激情", "情欲"
        )
        return strongKeywords.any { compact.contains(it) }
    }

    private fun parseSitesFromTvBoxText(text: String): List<Site> {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return emptyList()

        // 尝试解析路径：1. 原文 JSON 2. Base58 解码 3. Base64 解码
        var content = trimmed
        var element = runCatching { json.parseToJsonElement(content) }.getOrNull()

        if (element == null) {
            val decoded58 = runCatching { base58DecodeToString(trimmed) }.getOrNull()
            if (decoded58 != null && decoded58.contains("{")) {
                content = decoded58
                element = runCatching { json.parseToJsonElement(content) }.getOrNull()
            }
        }

        if (element == null) {
            val decoded64 = runCatching { 
                String(Base64.decode(trimmed, Base64.DEFAULT), StandardCharsets.UTF_8) 
            }.getOrNull()
            if (decoded64 != null && decoded64.contains("{")) {
                content = decoded64
                element = runCatching { json.parseToJsonElement(content) }.getOrNull()
            }
        }

        if (element == null) return emptyList()

        return when (element) {
            is JsonObject -> {
                val sitesArr = element["sites"] as? JsonArray
                if (sitesArr != null) {
                    parseSitesArray(sitesArr)
                } else {
                    val apiSite = element["api_site"] as? JsonObject
                    if (apiSite != null) {
                        parseSitesFromApiSite(apiSite)
                    } else {
                        searchSitesDeeply(element)
                    }
                }
            }
            is JsonArray -> parseSitesArray(element)
            else -> emptyList()
        }
    }

    private fun searchSitesDeeply(element: JsonElement): List<Site> {
        val results = mutableListOf<Site>()
        when (element) {
            is JsonObject -> {
                val sites = element["sites"]
                if (sites is JsonArray) {
                    results.addAll(parseSitesArray(sites))
                }

                val api = (element.stringOrNull("api") ?: element.stringOrNull("url"))?.trim()
                val name = element.stringOrNull("name")?.trim()
                if (!api.isNullOrBlank() && !name.isNullOrBlank() && isSupportedCmsApi(api)) {
                    val key = element.stringOrNull("key") ?: element.stringOrNull("id") ?: api.hashCode().toString()
                    results.add(Site(key = key, name = name, api = api, type = 1))
                }

                element.values.forEach { results.addAll(searchSitesDeeply(it)) }
            }
            is JsonArray -> {
                element.forEach { results.addAll(searchSitesDeeply(it)) }
            }
            else -> {}
        }
        return results.distinctBy { it.api }
    }

    private fun parseSitesArray(arr: JsonArray): List<Site> {
        return arr.mapNotNull { siteEl ->
            val obj = siteEl as? JsonObject ?: return@mapNotNull null
            val api = (obj.stringOrNull("api") ?: obj.stringOrNull("url"))?.trim().orEmpty()
            val name = obj.stringOrNull("name")?.trim().orEmpty()
            if (api.isBlank() || name.isBlank()) return@mapNotNull null

            val key = obj.stringOrNull("key") ?: obj.stringOrNull("id") ?: api.hashCode().toString()
            Site(key = key, name = name, type = 1, api = api)
        }
    }

    private fun parseSitesFromApiSite(apiSite: JsonObject): List<Site> {
        return apiSite.entries.mapNotNull { (k, v) ->
            val obj = v as? JsonObject ?: return@mapNotNull null
            val api = (obj.stringOrNull("api") ?: obj.stringOrNull("url"))?.trim().orEmpty()
            val name = obj.stringOrNull("name")?.trim().orEmpty()
            if (api.isBlank() || name.isBlank()) return@mapNotNull null
            Site(key = k, name = name, type = 1, api = api)
        }
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.content

    private fun isSupportedCmsApi(api: String): Boolean {
        val normalized = api.trim().lowercase()
        return normalized.contains("provide/vod") ||
            normalized.contains("api_mac") ||
            normalized.contains("apijson.php") ||
            normalized.contains("inc/api.php") ||
            normalized.contains("/api.php") ||
            normalized.contains("xml") ||
            normalized.contains("rss.php") ||
            normalized.contains("/rss") ||
            normalized.endsWith(".xml")
    }

    private fun base58DecodeToString(input: String): String {
        val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        val indexMap = IntArray(128) { -1 }
        for (i in alphabet.indices) indexMap[alphabet[i].code] = i

        val inputClean = input.replace(Regex("\\s"), "")
        var num = BigInteger.ZERO
        for (c in inputClean) {
            val value = indexMap.getOrNull(c.code) ?: continue
            if (value == -1) continue
            num = num.multiply(BigInteger.valueOf(58L)).add(BigInteger.valueOf(value.toLong()))
        }

        val bytes = num.toByteArray()
        // 去除 BigInteger 可能生成的符号位 00
        val startIndex = if (bytes.isNotEmpty() && bytes[0] == 0.toByte()) 1 else 0
        
        var zeros = 0
        while (zeros < inputClean.length && inputClean[zeros] == '1') zeros++
        
        val result = ByteArray(zeros + (bytes.size - startIndex))
        for (i in 0 until zeros) result[i] = 0.toByte()
        System.arraycopy(bytes, startIndex, result, zeros, bytes.size - startIndex)
        
        return String(result, StandardCharsets.UTF_8)
    }
}
