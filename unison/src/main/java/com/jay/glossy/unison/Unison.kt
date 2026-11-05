package com.jay.glossy.unison

import com.jay.glossy.unison.models.LyricsData
import com.jay.glossy.unison.models.LyricsResponse
import com.jay.glossy.unison.models.SearchResponse
import com.metrolist.music.betterlyrics.TTMLParser
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpHeaders
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlin.math.abs

object Unison {

    private const val BASE_URL = "https://unison.boidu.dev"

    private val client by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(
                    Json {
                        isLenient = true
                        ignoreUnknownKeys = true
                    },
                )
            }
            install(ContentEncoding) {
                gzip()
                deflate()
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 15_000
                connectTimeoutMillis = 8_000
                socketTimeoutMillis = 12_000
            }
            defaultRequest {
                url(BASE_URL)
                header(HttpHeaders.Accept, "application/json")
                header(HttpHeaders.UserAgent, "Glossy/1.0 (+https://unison.boidu.dev)")
            }
            expectSuccess = false
        }
    }

    private val titleCleanupPatterns = listOf(
        Regex("""^\s*[\[(【《〈＜].*?[\])】》〉＞]\s*"""),
        Regex(
            """\s*[\[(【《〈＜].*?(official|video|audio|lyrics|lyric|visualizer|hd|hq|4k|remaster|remix|live|acoustic|version|edit|extended|radio|clean|explicit).*?[\])】》〉＞]""",
            RegexOption.IGNORE_CASE,
        ),
        Regex("""\s*\|.*$"""),
        Regex("""\s*-\s*(official|video|audio|lyrics|lyric|visualizer).*$""", RegexOption.IGNORE_CASE),
        Regex("""\s*\(feat\..*?\)""", RegexOption.IGNORE_CASE),
        Regex("""\s*\(ft\..*?\)""", RegexOption.IGNORE_CASE),
        Regex("""\s*feat\..*$""", RegexOption.IGNORE_CASE),
        Regex("""\s*ft\..*$""", RegexOption.IGNORE_CASE),
    )

    private val artistSeparators =
        listOf(" & ", " and ", ", ", " x ", " X ", " feat. ", " feat ", " ft. ", " ft ", " featuring ", " with ")

    private val lrcLineTagRegex = Regex("""^\[\d{1,2}:\d{2}(?:[.:]\d{1,3})?\]""")

    private fun cleanTitle(title: String): String {
        var cleaned = title.trim()
        for (pattern in titleCleanupPatterns) cleaned = cleaned.replace(pattern, "")
        return cleaned.trim().ifBlank { title.trim() }
    }

    private fun cleanArtist(artist: String): String {
        var cleaned = artist.trim()
        for (sep in artistSeparators) {
            if (cleaned.contains(sep, ignoreCase = true)) {
                cleaned = cleaned.split(sep, ignoreCase = true, limit = 2)[0]
                break
            }
        }
        return cleaned.trim().ifBlank { artist.trim() }
    }

    private fun isSyncedItem(item: LyricsData): Boolean {
        val sync = item.syncType?.lowercase()
        if (sync == "richsync" || sync == "linesync") return true
        val fmt = item.format?.lowercase()
        return fmt == "ttml" || fmt == "lrc"
    }

    private fun syncQualityRank(item: LyricsData): Int = when (item.syncType?.lowercase()) {
        "richsync" -> 3
        "linesync" -> 2
        else -> when (item.format?.lowercase()) {
            "ttml" -> 3
            "lrc" -> 2
            else -> 1
        }
    }

    /**
     * Important: On the Better Lyrics Unison server (`GET /lyrics`), passing `v`
     * runs in an isolated videoId branch that immediately returns 404 when no
     * entry matches `videoId` without falling back to `song` + `artist`.
     * Video-ID and metadata queries must therefore be separate requests.
     */
    private suspend fun fetchByVideoId(videoId: String): LyricsData? = runSuspendCatching {
        val response = client.get("/lyrics") {
            parameter("v", videoId)
        }
        if (!response.status.isSuccess()) return@runSuspendCatching null
        response.body<LyricsResponse>().takeIf { it.success }?.data
    }

    private suspend fun fetchVariantsByVideoId(videoId: String, limit: Int = 10): List<LyricsData> =
        runSuspendCatching {
            val encodedId = videoId.encodeURLParameter()
            val response = client.get("/lyrics/variants/$encodedId") {
                parameter("limit", limit)
            }
            if (!response.status.isSuccess()) return@runSuspendCatching emptyList()
            response.body<SearchResponse>().takeIf { it.success }?.data ?: emptyList()
        } ?: emptyList()

    private suspend fun fetchById(id: Long): LyricsData? = runSuspendCatching {
        val response = client.get("/lyrics/$id")
        if (!response.status.isSuccess()) return@runSuspendCatching null
        response.body<LyricsResponse>().takeIf { it.success }?.data
    }

    private suspend fun fetchByMetadata(
        song: String,
        artist: String,
        album: String? = null,
        duration: Int? = null,
    ): LyricsData? = runSuspendCatching {
        val response = client.get("/lyrics") {
            parameter("song", song)
            parameter("artist", artist)
            if (!album.isNullOrBlank()) parameter("album", album)
            if (duration != null && duration > 0) parameter("duration", duration)
        }
        if (!response.status.isSuccess()) return@runSuspendCatching null
        response.body<LyricsResponse>().takeIf { it.success }?.data
    }

    private suspend fun searchStructured(
        song: String,
        artist: String,
        album: String? = null,
        duration: Int? = null,
    ): List<LyricsData> = runSuspendCatching {
        val response = client.get("/lyrics/search") {
            parameter("song", song)
            parameter("artist", artist)
            if (!album.isNullOrBlank()) parameter("album", album)
            if (duration != null && duration > 0) parameter("duration", duration)
        }
        if (!response.status.isSuccess()) return@runSuspendCatching emptyList()
        response.body<SearchResponse>().takeIf { it.success }?.data ?: emptyList()
    } ?: emptyList()

    private suspend fun searchQuery(
        query: String,
        limit: Int = 8,
    ): List<LyricsData> = runSuspendCatching {
        val response = client.get("/lyrics/search") {
            parameter("q", query)
            parameter("limit", limit)
        }
        if (!response.status.isSuccess()) return@runSuspendCatching emptyList()
        response.body<SearchResponse>().takeIf { it.success }?.data ?: emptyList()
    } ?: emptyList()

    private inline fun <T> runSuspendCatching(block: () -> T): T? {
        return try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
    }

    private fun convertLyrics(raw: String, format: String?): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        val isTtml =
            format.equals("ttml", ignoreCase = true) ||
                trimmed.startsWith("<tt", ignoreCase = true) ||
                trimmed.startsWith("<?xml", ignoreCase = true)

        return if (isTtml) {
            val parsed = TTMLParser.parseTTML(trimmed)
            if (parsed.isNotEmpty()) {
                TTMLParser.toLRC(parsed).takeIf { it.isNotBlank() }
            } else {
                null
            }
        } else {
            // Preserve rich-sync word tags (<MM:SS.xx>) untouched; on standard LRC lines,
            // map parenthesised backing vocals to v2: agent syntax.
            trimmed.lines().joinToString("\n") { line ->
                if (line.contains('<') && line.contains('>')) {
                    line
                } else {
                    val match = lrcLineTagRegex.find(line)
                    if (match != null) {
                        val timeTag = match.value
                        val textPart = line.substring(match.range.last + 1).trim()
                        if (textPart.startsWith("(") && textPart.endsWith(")") && textPart.length > 2) {
                            "$timeTag v2: ${textPart.removeSurrounding("(", ")")}"
                        } else {
                            line
                        }
                    } else {
                        line
                    }
                }
            }.takeIf { it.isNotBlank() }
        }
    }

    suspend fun getLyrics(
        title: String,
        artist: String,
        duration: Int,
        album: String? = null,
        videoId: String? = null,
    ): Result<String> = runCatching {
        val rawTitle = title.trim()
        val rawArtist = artist.trim()
        val cleanedTitle = cleanTitle(rawTitle)
        val cleanedArtist = cleanArtist(rawArtist)
        val effectiveDuration = duration.takeIf { it > 0 }
        val cleanVideoId = videoId?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("LA") }

        var plainFallback: String? = null

        fun acceptCandidate(item: LyricsData?): String? {
            if (item == null) return null
            val raw = item.lyrics ?: return null
            val converted = convertLyrics(raw, item.format) ?: return null
            return if (isSyncedItem(item)) {
                converted
            } else {
                if (plainFallback == null) plainFallback = converted
                null
            }
        }

        // 1. Exact YouTube videoId lookup (isolated from song/artist so Unison server works properly)
        if (cleanVideoId != null) {
            fetchByVideoId(cleanVideoId)?.let { direct ->
                acceptCandidate(direct)?.let { return@runCatching it }
            }
            val variants = fetchVariantsByVideoId(cleanVideoId, limit = 5)
            if (variants.isNotEmpty()) {
                val sortedVariants = variants.sortedWith(
                    compareByDescending<LyricsData> { syncQualityRank(it) }
                        .thenByDescending { it.effectiveScore ?: it.score ?: 0.0 },
                )
                for (variant in sortedVariants) {
                    acceptCandidate(variant)?.let { return@runCatching it }
                }
            }
        }

        // 2. Metadata lookup: try strict (with album & duration) first, then relaxed (without ±2s duration gate)
        val queryPairs = buildList {
            if (rawTitle.isNotEmpty() && rawArtist.isNotEmpty()) {
                add(rawTitle to rawArtist)
            }
            if (cleanedTitle.isNotEmpty() && cleanedArtist.isNotEmpty() &&
                (!cleanedTitle.equals(rawTitle, ignoreCase = true) || !cleanedArtist.equals(rawArtist, ignoreCase = true))
            ) {
                add(cleanedTitle to cleanedArtist)
            }
            if (cleanedTitle.isNotEmpty() && rawArtist.isNotEmpty() &&
                !cleanedTitle.equals(rawTitle, ignoreCase = true) &&
                !cleanedArtist.equals(rawArtist, ignoreCase = true)
            ) {
                add(cleanedTitle to rawArtist)
            }
        }

        for ((qSong, qArtist) in queryPairs) {
            currentCoroutineContext().ensureActive()
            if (!album.isNullOrBlank() || effectiveDuration != null) {
                fetchByMetadata(
                    song = qSong,
                    artist = qArtist,
                    album = album,
                    duration = effectiveDuration,
                )?.let { item ->
                    acceptCandidate(item)?.let { return@runCatching it }
                }
            }
            fetchByMetadata(
                song = qSong,
                artist = qArtist,
            )?.let { item ->
                acceptCandidate(item)?.let { return@runCatching it }
            }
        }

        // 3. Structured search (ranks multiple variants by sync quality, score, similarity & duration)
        val searchCandidates = mutableListOf<LyricsData>()
        if (cleanedTitle.isNotEmpty() && cleanedArtist.isNotEmpty()) {
            searchCandidates += searchStructured(song = cleanedTitle, artist = cleanedArtist)
        }
        if (searchCandidates.isEmpty() && rawTitle.isNotEmpty() && rawArtist.isNotEmpty() &&
            (!cleanedTitle.equals(rawTitle, ignoreCase = true) || !cleanedArtist.equals(rawArtist, ignoreCase = true))
        ) {
            searchCandidates += searchStructured(song = rawTitle, artist = rawArtist)
        }

        if (searchCandidates.isNotEmpty()) {
            val ranked = sortResults(searchCandidates, cleanedTitle, cleanedArtist, duration)
            for (candidate in ranked) {
                val hydrated = if (candidate.lyrics.isNullOrBlank()) fetchById(candidate.id) else candidate
                acceptCandidate(hydrated)?.let { return@runCatching it }
            }
        }

        // 4. Full-text search fallback (`/lyrics/search?q=...`) hydrated via `/lyrics/:id`
        val queryText = "$cleanedTitle $cleanedArtist".trim()
        if (queryText.isNotEmpty()) {
            val fuzzyResults = searchQuery(queryText, limit = 6)
            val viableFuzzy = sortResults(fuzzyResults, cleanedTitle, cleanedArtist, duration)
                .filter { candidate ->
                    val titleSim = maxOf(
                        stringSimilarity(cleanedTitle, candidate.song.orEmpty()),
                        stringSimilarity(rawTitle, candidate.song.orEmpty()),
                    )
                    val artistSim = maxOf(
                        stringSimilarity(cleanedArtist, candidate.artist.orEmpty()),
                        stringSimilarity(rawArtist, candidate.artist.orEmpty()),
                    )
                    titleSim >= 0.65 && artistSim >= 0.55
                }
            for (candidate in viableFuzzy.take(3)) {
                currentCoroutineContext().ensureActive()
                val hydrated = if (candidate.lyrics.isNullOrBlank()) fetchById(candidate.id) else candidate
                acceptCandidate(hydrated)?.let { return@runCatching it }
            }
        }

        plainFallback ?: throw IllegalStateException("Lyrics unavailable on Better Lyrics Unison")
    }

    suspend fun getAllLyrics(
        title: String,
        artist: String,
        duration: Int,
        album: String? = null,
        videoId: String? = null,
        callback: (String) -> Unit,
    ) {
        val rawTitle = title.trim()
        val rawArtist = artist.trim()
        val cleanedTitle = cleanTitle(rawTitle)
        val cleanedArtist = cleanArtist(rawArtist)
        val cleanVideoId = videoId?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("LA") }

        val collectedById = LinkedHashMap<Long, LyricsData>()

        if (cleanVideoId != null) {
            for (variant in fetchVariantsByVideoId(cleanVideoId, limit = 10)) {
                collectedById[variant.id] = variant
            }
            if (collectedById.isEmpty()) {
                fetchByVideoId(cleanVideoId)?.let { collectedById[it.id] = it }
            }
        }

        if (cleanedTitle.isNotEmpty() && cleanedArtist.isNotEmpty()) {
            for (item in searchStructured(song = cleanedTitle, artist = cleanedArtist)) {
                val existing = collectedById[item.id]
                if (existing == null || (existing.lyrics.isNullOrBlank() && !item.lyrics.isNullOrBlank())) {
                    collectedById[item.id] = item
                }
            }
        }

        if (collectedById.size < 3) {
            val queryText = "$cleanedTitle $cleanedArtist".trim()
            if (queryText.isNotEmpty()) {
                for (item in searchQuery(queryText, limit = 6)) {
                    if (!collectedById.containsKey(item.id)) {
                        collectedById[item.id] = item
                    }
                }
            }
        }

        val sorted = sortResults(collectedById.values.toList(), cleanedTitle, cleanedArtist, duration)
        val emittedPayloads = HashSet<Int>()
        var count = 0

        for (item in sorted) {
            currentCoroutineContext().ensureActive()
            if (count >= 8) break
            val hydrated = if (item.lyrics.isNullOrBlank()) fetchById(item.id) else item
            val raw = hydrated?.lyrics ?: continue
            val converted = convertLyrics(raw, hydrated.format) ?: continue
            if (emittedPayloads.add(converted.hashCode())) {
                callback(converted)
                count++
            }
        }
    }

    private fun sortResults(
        results: List<LyricsData>,
        title: String,
        artist: String,
        duration: Int,
    ): List<LyricsData> {
        return results.sortedByDescending { item ->
            val baseScore = (item.effectiveScore ?: item.score ?: item.matchScore ?: 0.0).coerceIn(-1.0, 2.0) * 0.30

            val titleSim = stringSimilarity(title, item.song ?: "")
            val artistSim = stringSimilarity(artist, item.artist ?: "")
            val simScore = (titleSim * 0.6 + artistSim * 0.4) * 0.40

            val syncBonus = when (syncQualityRank(item)) {
                3 -> 0.20 // richsync / word-synced TTML
                2 -> 0.12 // linesync / LRC
                else -> 0.0
            }

            val durationBonus = if (duration > 0 && item.duration != null) {
                val diff = abs(item.duration - duration)
                when {
                    diff <= 2.0 -> 0.10
                    diff <= 6.0 -> 0.06
                    diff <= 12.0 -> 0.02
                    else -> -0.05
                }
            } else {
                0.0
            }

            baseScore + simScore + syncBonus + durationBonus
        }
    }

    private fun stringSimilarity(a: String, b: String): Double {
        val s1 = a.trim().lowercase()
        val s2 = b.trim().lowercase()
        return when {
            s1 == s2 -> 1.0
            s1.isEmpty() || s2.isEmpty() -> 0.0
            s1.contains(s2) || s2.contains(s1) -> 0.85
            else -> {
                val maxLen = maxOf(s1.length, s2.length)
                1.0 - (levenshtein(s1, s2).toDouble() / maxLen)
            }
        }
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        // Single-row rolling array instead of (N+1)x(M+1) 2D allocations
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            val ca = a[i - 1]
            for (j in 1..b.length) {
                val cost = if (ca == b[j - 1]) 0 else 1
                curr[j] = minOf(curr[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[b.length]
    }
}
