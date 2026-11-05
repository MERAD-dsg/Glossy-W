/**
 * Glossy Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.jay.glossy.lyrics

import java.util.Locale

object LyricsProviderRegistry {
    private val providerMap = linkedMapOf(
        "Unison" to UnisonLyricsProvider,
        "BetterLyrics" to BetterLyricsProvider,
        "Spotify" to SpotifyLyricsProvider,
        "YouLyPlus" to YouLyPlusLyricsProvider,
        "Paxsenix" to PaxsenixLyricsProvider,
        "LyricsPlus" to LyricsPlusProvider,
        "LrcLib" to LrcLibLyricsProvider,
        "KuGou" to KuGouLyricsProvider,
        "NetEase" to NetEaseLyricsProvider,
        "BiniLyrics" to BiniLyricsProvider,
        "SimpMusic" to SimpMusicLyricsProvider,
        "Megalobiz" to MegalobizLyricsProvider,
        "YouTubeSubtitle" to YouTubeSubtitleLyricsProvider,
        "YouTube" to YouTubeLyricsProvider,
    )

    val providerNames = providerMap.keys.toList()

    fun getProviderByName(name: String): LyricsProvider? = providerMap[name]

    fun getProviderName(provider: LyricsProvider): String? =
        providerMap.entries.find { it.value == provider }?.key

    fun deserializeProviderOrder(orderString: String): List<String> {
        if (orderString.isBlank()) {
            return getDefaultProviderOrder()
        }
        // Two separators have shipped: the priority screen serialises with ","
        // while an older migration wrote ";". Accept both, match names
        // case-insensitively, and append any newly added providers at the end
        // so they remain visible in the priority screen and eligible in races.
        val byLowercaseName = providerNames.associateBy { it.lowercase(Locale.ROOT) }
        val configured = orderString
            .split(';', ',')
            .map { it.trim() }
            .mapNotNull { stored -> byLowercaseName[stored.lowercase(Locale.ROOT)] }
            .distinct()

        if (configured.isEmpty()) {
            return getDefaultProviderOrder()
        }

        val missing = getDefaultProviderOrder().filter { it !in configured }
        return configured + missing
    }

    fun serializeProviderOrder(providers: List<String>): String {
        return providers.filter { it in providerNames }.distinct().joinToString(",")
    }

    /**
     * Default lyrics provider priority order: Better Lyrics Unison leads by default.
     */
    fun getDefaultProviderOrder(): List<String> = listOf(
        "Unison",
        "BetterLyrics",
        "Spotify",
        "YouLyPlus",
        "Paxsenix",
        "LyricsPlus",
        "LrcLib",
        "KuGou",
        "NetEase",
        "BiniLyrics",
        "SimpMusic",
        "Megalobiz",
        "YouTubeSubtitle",
        "YouTube",
    )

    fun getOrderedProviders(orderString: String): List<LyricsProvider> {
        val order = deserializeProviderOrder(orderString)
        return order.mapNotNull { getProviderByName(it) }
    }
}
