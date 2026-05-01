package com.example.myapplicationlibretv.data.api

import com.example.myapplicationlibretv.BuildConfig
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit

object RetrofitClient {
    private val json = Json { 
        ignoreUnknownKeys = true 
        coerceInputValues = true
    }
    
    private val okHttpClient: OkHttpClient by lazy {
        val cacheDir = java.io.File("/data/user/0/${BuildConfig.APPLICATION_ID}/cache/http_cache")
        NetworkTuning.createTunedClient(
            cacheDirectory = cacheDir,
            trustAllSsl = true,
            bodyLogging = BuildConfig.DEBUG
        )
    }

    // 默认保底 API
    const val DEFAULT_API = "https://cj.lziapi.com/api.php/provide/vod/"

    val cmsApi: CmsApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://cj.lziapi.com/") // 占位符
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .addConverterFactory(json.asConverterFactory("text/plain".toMediaType()))
            .addConverterFactory(json.asConverterFactory("text/html".toMediaType()))
            .build()
            .create(CmsApi::class.java)
    }
}
