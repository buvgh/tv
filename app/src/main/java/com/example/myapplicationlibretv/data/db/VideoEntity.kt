package com.example.myapplicationlibretv.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "favorites")
data class FavoriteVideo(
    @PrimaryKey val id: Int,
    val name: String,
    val pic: String?,
    val siteKey: String = "",
    val sourceVideoId: Int = id,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "history")
data class HistoryVideo(
    @PrimaryKey val id: Int,
    val name: String,
    val pic: String?,
    val siteKey: String = "",
    val sourceVideoId: Int = id,
    val timestamp: Long = System.currentTimeMillis(),
    val progress: Long = 0,
    val duration: Long = 0
)

@Entity(tableName = "downloads")
data class DownloadVideo(
    @PrimaryKey val taskId: String,
    val title: String,
    val status: String,
    val progressText: String,
    val rawUrl: String,
    val fileName: String? = null,
    val fileUri: String? = null,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
