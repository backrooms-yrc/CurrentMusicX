package io.github.currencortex.music.core.media

import androidx.media3.common.PlaybackException
import io.github.currencortex.music.core.netease.NeteaseCrypto
import io.github.currencortex.music.core.netease.NeteaseGateway
import io.github.currencortex.music.core.netease.NeteaseSessionStore
import io.github.currencortex.music.core.netease.NeteaseTransport
import io.github.currencortex.music.core.network.ApiClient
import io.github.currencortex.music.core.network.ApiJson
import io.github.currencortex.music.core.network.RequestSession
import io.github.currencortex.music.core.security.TokenStore
import io.github.currencortex.music.data.settings.AudioOutputMode
import io.github.currencortex.music.data.settings.AudioProvider
import io.github.currencortex.music.data.settings.AudioSourceAccess
import io.github.currencortex.music.data.song.AudioSource
import io.github.currencortex.music.data.song.LeizAudioClient
import io.github.currencortex.music.data.song.MusicRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class DolbyPlaybackTest {
    @Test fun currentMusicOnlySendsCapabilityForSupportedAutoPlayback() = runBlocking {
        val requests = mutableListOf<Request>()
        val repository = currentMusic { request ->
            requests += request
            val actual = if (request.url.queryParameter("supportsDolby") == "1") "dolby" else "jymaster"
            track(actual)
        }
        val session = RequestSession("https://example.org/cm/", "test-token")
        assertEquals("dolby", repository.source(42, AudioQuality.AUTO, session, refresh = true, supportsDolby = true).level)
        assertEquals("jymaster", repository.source(42, AudioQuality.AUTO).level)
        assertEquals("jymaster", repository.source(42, AudioQuality.DOLBY, supportsDolby = true).level)
        assertEquals(listOf("auto", "auto", "dolby"), requests.map { it.url.queryParameter("level") })
        assertEquals(listOf("1", null, null), requests.map { it.url.queryParameter("supportsDolby") })
        assertEquals(listOf("1", null, null), requests.map { it.url.queryParameter("refresh") })
        assertTrue(requests.all { it.method == "GET" && it.url.encodedPath == "/cm/ncm/song/url" })
    }

    @Test fun currentMusicUsesActualLevelAndRetriesUnexpectedDolbyPreview() = runBlocking {
        val flags = mutableListOf<String?>()
        val repository = currentMusic { request ->
            flags += request.url.queryParameter("supportsDolby")
            when (flags.size) {
                1 -> track("hires")
                2 -> track("dolby", trial = """{"start":0,"end":30}""")
                else -> track("lossless")
            }
        }
        assertEquals("hires", repository.source(42, AudioQuality.AUTO, supportsDolby = true).level)
        assertEquals("lossless", repository.source(42, AudioQuality.AUTO, supportsDolby = true).level)
        assertEquals(listOf("1", "1", null), flags)
    }

    @Test fun nativeSelectsFullDolbyAndPreservesUnsupportedAutoAndManualRequests() = runBlocking {
        val levels = mutableListOf<String>()
        val repository = native(this) { level -> levels += level; track(level) }
        assertEquals("dolby", repository.source(42, AudioQuality.AUTO, supportsDolby = true).level)
        assertEquals("jymaster", repository.source(42, AudioQuality.AUTO).level)
        assertEquals("dolby", repository.source(42, AudioQuality.DOLBY).level)
        assertEquals("lossless", repository.source(42, AudioQuality.LOSSLESS, supportsDolby = true).level)
        assertEquals(listOf("dolby", "jymaster", "dolby", "lossless"), levels)
    }

    @Test fun nativeReissuesJymasterAfterDowngradePreviewOrMissingSource() = runBlocking {
        val rejected = listOf(
            track("hires"),
            track("dolby", trial = """{"start":0,"end":30}"""),
            track("dolby", url = "not an address"),
            track("dolby", url = ""),
            track("dolby", code = 404),
            null,
        )
        for (candidate in rejected) {
            val levels = mutableListOf<String>()
            val repository = native(this) { level ->
                levels += level
                if (level == "dolby") candidate else track("lossless")
            }
            assertEquals("lossless", repository.source(42, AudioQuality.AUTO, supportsDolby = true).level)
            assertEquals(listOf("dolby", "jymaster"), levels)
        }
    }

    @Test fun leizKeepsItsOwnAutoAndManualProtocol() = runBlocking {
        val requests = mutableListOf<Request>()
        val leiz = LeizAudioClient("https://example.org/leiz", client { request ->
            requests += request
            """{"success":true,"data":${track(requireNotNull(request.url.queryParameter("level")))}}"""
        })
        val repository = MusicRepository(ApiClient({ "https://example.org/" }, { null }, {}),
            { AudioSourceAccess(AudioProvider.LEIZ, "test-key") }, leiz)
        assertEquals("jymaster", repository.source(42, AudioQuality.AUTO, supportsDolby = true).level)
        assertEquals("dolby", repository.source(42, AudioQuality.DOLBY, supportsDolby = true).level)
        assertEquals(listOf("jymaster", "dolby"), requests.map { it.url.queryParameter("level") })
        assertTrue(requests.all { it.url.queryParameter("supportsDolby") == null })
    }

    @Test fun cacheScopesAreDistinctForAutomaticDolbyButUnchangedForManualQuality() {
        val regular = AudioRequest(42, AudioQuality.AUTO, 3, RequestSession("https://example.org/", null))
        val preferred = regular.copy(supportsDolby = true)
        assertNotEquals(regular.key, preferred.key)
        assertEquals(regular.key, regular.copy(supportsDolby = false).key)
        val manual = regular.copy(quality = AudioQuality.DOLBY)
        assertEquals(manual.key, manual.copy(supportsDolby = true).key)
        assertNotEquals(manual.key, preferred.key)
        assertFalse(regular.accepts(AudioSource("cache://audio/test", "dolby", 0, 0)))
    }

    @Test fun localPcmRouteAndDecoderAreBothRequired() {
        fun supports(mode: PlayerMode = PlayerMode.LOCAL, output: AudioOutputMode = AudioOutputMode.AUDIO_TRACK,
            route: Boolean = true, decoder: Boolean = true) = canPreferDolby(mode, output, { route }, { decoder })
        assertTrue(supports())
        assertFalse(supports(route = false))
        assertFalse(supports(decoder = false))
        assertFalse(supports(mode = PlayerMode.CAST))
        assertFalse(supports(mode = PlayerMode.ROOM))
        AudioOutputMode.entries.filter { it != AudioOutputMode.AUDIO_TRACK }.forEach { assertFalse(supports(output = it)) }
    }

    @Test fun simulatedDecoderOrOutputFailureClearsDolbyOnlyForTheRetry() = runBlocking {
        val levels = mutableListOf<String>()
        val repository = native(this) { level -> levels += level; track(level) }
        val request = AudioRequest(42, AudioQuality.AUTO, 3, RequestSession("https://music.163.com/", null),
            providerIdentity = "netease", supportsDolby = true)
        val first = repository.source(request.songId, request.quality, supportsDolby = request.supportsDolby)
        assertTrue(request.selectedDolby(first))
        assertTrue(isDolbyPlaybackFailure(PlaybackException.ERROR_CODE_DECODING_FAILED))
        assertTrue(isDolbyPlaybackFailure(PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED))
        assertFalse(isDolbyPlaybackFailure(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        val state = PlayerState()
        assertEquals(DolbyResume(51_200, true), dolbyResume(request.selectedDolby(first), true, state, request.quality, 51_200, true))
        assertEquals(DolbyResume(51_200, false), dolbyResume(request.selectedDolby(first), true, state, request.quality, 51_200, false))
        assertNull(dolbyResume(true, false, state, request.quality, 51_200, true))
        assertNull(dolbyResume(true, true, state.copy(resolving = true), request.quality, 51_200, true))
        val retry = request.copy(supportsDolby = false)
        val regular = repository.source(retry.songId, retry.quality, supportsDolby = retry.supportsDolby)
        assertEquals(listOf("dolby", "jymaster"), levels)
        assertNull(dolbyResume(retry.selectedDolby(regular), true, state, retry.quality, 51_200, true))
        val manual = request.copy(quality = AudioQuality.DOLBY)
        assertNull(dolbyResume(manual.selectedDolby(first), true, state, manual.quality, 51_200, true))
        assertTrue(request.selectedDolby(first))
        assertEquals(AudioQuality.AUTO, retry.quality)
    }

    private fun currentMusic(respond: (Request) -> String): MusicRepository = MusicRepository(ApiClient(
        { "https://example.org/cm/" }, { "test-token" }, {}, client = client(respond)))

    private fun native(scope: CoroutineScope, respond: (String) -> String?): MusicRepository {
        val store = NeteaseSessionStore(object : TokenStore {
            override fun read(): String? = null
            override fun write(token: String) = Unit
            override fun clear() = Unit
        }, scope)
        val gateway = NeteaseGateway(NeteaseTransport(store, client = client { request ->
            val form = Buffer().apply { requireNotNull(request.body).writeTo(this) }.readUtf8()
            val encoded = form.substringAfter("params=").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val payload = NeteaseCrypto.decryptResponse(encoded).decodeToString().split("-36cd479b6b5-")[1]
            val level = ApiJson.parseToJsonElement(payload).jsonObject.getValue("level").jsonPrimitive.content
            """{"code":200,"data":[${respond(level).orEmpty()}]}"""
        }))
        return MusicRepository(ApiClient({ "https://example.org/" }, { null }, {}, netease = gateway),
            { AudioSourceAccess(AudioProvider.NETEASE) })
    }

    private fun client(respond: (Request) -> String) = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(respond(request).toResponseBody("application/json".toMediaType())).build()
    }.build()

    private fun track(level: String, trial: String = "null", url: String = "https://example.org/audio", code: Int = 200) =
        """{"id":42,"level":"$level","url":"$url","code":$code,"freeTrialInfo":$trial}"""
}
