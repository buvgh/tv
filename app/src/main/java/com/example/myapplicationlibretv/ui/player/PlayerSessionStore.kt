package com.example.myapplicationlibretv.ui.player

import com.example.myapplicationlibretv.ui.detail.PlayerEpisodePayload
import java.util.concurrent.ConcurrentHashMap

data class PlayerSession(
    val episodes: List<PlayerEpisodePayload>,
    val currentEpisodeIndex: Int,
    val historyRecordId: Int = 0
)

object PlayerSessionStore {
    private val sessions = ConcurrentHashMap<String, PlayerSession>()

    fun put(session: PlayerSession): String {
        val id = System.currentTimeMillis().toString()
        sessions[id] = session
        return id
    }

    fun get(sessionId: String?): PlayerSession? {
        if (sessionId.isNullOrBlank()) return null
        return sessions[sessionId]
    }
}
