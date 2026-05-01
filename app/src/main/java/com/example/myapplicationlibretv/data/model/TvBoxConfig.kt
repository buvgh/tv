package com.example.myapplicationlibretv.data.model

import kotlinx.serialization.Serializable

@Serializable
data class TvBoxConfig(
    val sites: List<Site> = emptyList()
)

@Serializable
data class Site(
    val key: String? = null,
    val name: String,
    val type: Int? = null,
    val api: String,
    val searchable: Int? = null,
    val quickSearch: Int? = null,
    val filterable: Int? = null,
    val ext: String? = null
)
