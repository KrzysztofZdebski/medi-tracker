package pl.agh.meditracker.data.catalog

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CatalogRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    @Test
    fun usesConfiguredBaseUrl() = runBlocking {
        // schemat inny niż obsługiwany, więc kończy się na version.json
        server.enqueue(MockResponse().setBody("""{"schema":99,"stan_na_dzien":"2026-01-01","sha256":"x","size":1}"""))
        val repo = CatalogRepository(tmp.newFolder(), server.url("/catalog").toString().trimEnd('/'))
        assertEquals(CatalogSync.Unsupported, repo.sync())
        assertEquals("/catalog/version.json", server.takeRequest().path)
    }
}
