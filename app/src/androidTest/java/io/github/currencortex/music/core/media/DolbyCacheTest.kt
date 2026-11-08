package io.github.currencortex.music.core.media

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.currencortex.music.core.network.RequestSession
import io.github.currencortex.music.data.song.AudioSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class DolbyCacheTest {
    @Test fun prefetchMemoAndPersistentBytesFollowTheCapabilityOfEachRequest() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File.createTempFile("dolby-cache", null, context.cacheDir)
        check(directory.delete() && directory.mkdir())
        val session = RequestSession("https://example.org/", "test-token")
        val regular = AudioRequest(42, AudioQuality.AUTO, 3, session)
        val preferred = regular.copy(supportsDolby = true)
        try {
            MockWebServer().use { cdn ->
                cdn.enqueue(MockResponse().setBody("Dolby bytes"))
                cdn.enqueue(MockResponse().setBody("Regular bytes"))
                AudioCache(context, directory).use { cache ->
                    val requests = mutableListOf<Boolean>()
                    val resolver = AudioSourceResolver(cache, { session }, loadTrack = { request ->
                        requests += request.supportsDolby
                        val level = if (request.supportsDolby) "dolby" else "jymaster"
                        AudioSource(cdn.url("/$level").toString(), level, 0, 0)
                    }, load = { _, _, _ -> error("Expected a scoped request") })

                    val prefetch = resolver.resolve(preferred)
                    val unsupported = resolver.resolve(regular)
                    assertEquals(prefetch, resolver.resolve(preferred).copy(refreshAfterMs = prefetch.refreshAfterMs))
                    assertEquals(unsupported, resolver.resolve(regular).copy(refreshAfterMs = unsupported.refreshAfterMs))
                    assertEquals(listOf(true, false), requests)
                    assertEquals("dolby", prefetch.source.level)
                    assertEquals("jymaster", unsupported.source.level)
                    assertEquals("Dolby bytes", read(cache, prefetch))
                    assertEquals("Regular bytes", read(cache, unsupported))
                }
            }
            AudioCache(context, directory).use { cache ->
                val offline = AudioSourceResolver(cache, { session }, load = { _, _, _ -> error("Expected disk cache hit") })
                val supported = offline.resolve(preferred)
                val unsupported = offline.resolve(regular)
                assertTrue(supported.source.url.startsWith("cache://audio/"))
                assertTrue(unsupported.source.url.startsWith("cache://audio/"))
                assertEquals("dolby", supported.source.level)
                assertEquals("jymaster", unsupported.source.level)
                assertEquals("Dolby bytes", read(cache, supported))
                assertEquals("Regular bytes", read(cache, unsupported))
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun read(cache: AudioCache, resolved: ResolvedAudio): String {
        val source = cache.dataSourceFactory.createDataSource()
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(256)
        try {
            source.open(DataSpec.Builder().setUri(Uri.parse(resolved.source.url)).setKey(resolved.key).build())
            while (true) {
                val count = source.read(buffer, 0, buffer.size)
                if (count < 0) break
                bytes.write(buffer, 0, count)
            }
        } finally {
            source.close()
        }
        return bytes.toString(Charsets.UTF_8.name())
    }
}
