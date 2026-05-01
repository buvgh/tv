package com.example.myapplicationlibretv.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CmsResponse(
    val code: Int? = null,
    val msg: String? = null,
    val page: Int? = null,
    val pagecount: Int? = null,
    val pagesize: Int? = null,
    val total: Int? = null,
    val list: List<VideoItem> = emptyList()
)

@Serializable
data class VideoItem(
    @SerialName("vod_id") val id: Int,
    @SerialName("vod_name") val name: String,
    @SerialName("type_id") val typeId: Int? = null,
    @SerialName("type_name") val typeName: String? = null,
    @SerialName("vod_en") val enName: String? = null,
    @SerialName("vod_actor") val actor: String? = null,
    @SerialName("vod_director") val director: String? = null,
    @SerialName("vod_area") val area: String? = null,
    @SerialName("vod_lang") val lang: String? = null,
    @SerialName("vod_year") val year: String? = null,
    @SerialName("vod_time") val time: String? = null,
    @SerialName("vod_remarks") val remarks: String? = null,
    @SerialName("vod_play_from") val playFrom: String? = null,
    @SerialName("vod_play_url") val playUrl: String? = null,
    @SerialName("vod_pic") val pic: String? = null,
    @SerialName("vod_content") val content: String? = null
)
