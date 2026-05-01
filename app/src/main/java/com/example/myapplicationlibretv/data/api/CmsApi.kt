package com.example.myapplicationlibretv.data.api

import com.example.myapplicationlibretv.data.model.CmsResponse
import com.example.myapplicationlibretv.data.model.TvBoxConfig
import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Query

interface CmsApi {
    @GET
    suspend fun getVideos(
        @retrofit2.http.Url url: String,
        @Query("ac") action: String = "videolist",
        @Query("t") typeId: Int? = null,
        @Query("pg") page: Int = 1,
        @Query("wd") keyword: String? = null,
        @Query("ids") ids: String? = null
    ): CmsResponse

    @GET
    suspend fun getTvBoxConfig(@retrofit2.http.Url url: String): TvBoxConfig

    @GET
    suspend fun getRaw(@retrofit2.http.Url url: String): ResponseBody
}
