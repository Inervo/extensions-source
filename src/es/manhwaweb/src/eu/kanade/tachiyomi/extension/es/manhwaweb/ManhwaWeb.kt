package eu.kanade.tachiyomi.extension.es.manhwaweb

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimitHost
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class ManhwaWeb : HttpSource(), ConfigurableSource {

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override val name = "ManhwaWeb"

    override val baseUrl = "https://manhwaweb.com"

    private val apiUrl = "https://manhwawebbackend-production.up.railway.app"

    override val lang = "es"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .rateLimitHost(baseUrl.toHttpUrl(), 2)
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    override fun popularMangaRequest(page: Int): Request = GET("$apiUrl/manhwa/nuevos", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val result = json.decodeFromString<PayloadPopularDto>(response.body.string())
        val mangas = (result.data.weekly + result.data.total)
            .distinctBy { it.slug }
            .sortedByDescending { it.views }
            .map { it.toSManga() }

        return MangasPage(mangas, false)
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$apiUrl/latest/new-manhwa", headers)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val result = json.decodeFromString<PayloadLatestDto>(response.body.string())
        val mangas = (result.data.esp + result.data.raw18 + result.data.esp18)
            .distinctBy { it.type + it.slug }
            .sortedByDescending { it.latestChapterDate }
            .map { it.toSManga() }

        return MangasPage(mangas, false)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$apiUrl/manhwa/library".toHttpUrl().newBuilder()
            .addQueryParameter("buscar", query)

        filters.forEach { filter ->
            when (filter) {
                is TypeFilter -> url.addQueryParameter("tipo", filter.toUriPart())
                is DemographyFilter -> url.addQueryParameter("demografia", filter.toUriPart())
                is StatusFilter -> url.addQueryParameter("estado", filter.toUriPart())
                is EroticFilter -> url.addQueryParameter("erotico", filter.toUriPart())
                is GenreFilter -> {
                    val genres = filter.state
                        .filter { it.state }
                        .joinToString("a") { it.id.toString() }
                    url.addQueryParameter("generes", genres)
                }

                is SortByFilter -> {
                    url.addQueryParameter(
                        "order_dir",
                        if (filter.state!!.ascending) "asc" else "desc",
                    )
                    url.addQueryParameter("order_item", filter.selected)
                }

                else -> {}
            }
        }

        url.addQueryParameter("page", (page - 1).toString())

        return GET(url.build(), headers)
    }

    override fun getFilterList(): FilterList {
        return FilterList(
            TypeFilter(),
            DemographyFilter(),
            StatusFilter(),
            EroticFilter(),
            Filter.Separator(),
            GenreFilter("Géneros", getGenres()),
            Filter.Separator(),
            SortByFilter("Ordenar por", getSortProperties()),
        )
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val result = json.decodeFromString<PayloadSearchDto>(response.body.string())
        val mangas = result.data.map { it.toSManga() }
        return MangasPage(mangas, result.hasNextPage)
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/${manga.url}"

    override fun mangaDetailsRequest(manga: SManga): Request {
        val slug = manga.url.removeSuffix("/").substringAfterLast("/")
        return GET("$apiUrl/manhwa/see/$slug", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga =
        json.decodeFromString<ComicDetailsDto>(response.body.string()).toSManga()

    override fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url

    override fun chapterListRequest(manga: SManga) = mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> {
        val result = json.decodeFromString<PayloadChapterDto>(response.body.string())
        val chaptersEsp = result.esp.map { it.toSChapter("Esp") }
        val chaptersRaw = result.raw.map { it.toSChapter("Raw") }

        val filteredRaws = if (preferences.showAllRawsPref()) {
            chaptersRaw
        } else {
            val chapterNumbers = chaptersEsp.map { it.chapter_number }.toSet()
            chaptersRaw.filter { it.chapter_number !in chapterNumbers }
        }

        return (chaptersEsp + filteredRaws).sortedByDescending { it.chapter_number }
    }

    private fun ChapterDto.toSChapter(type: String) = SChapter.create().apply {
        name = "Capítulo ${number.toString().removeSuffix(".0")}"
        chapter_number = number
        date_upload = createdAt ?: 0
        setUrlWithoutDomain(url)
        scanlator = type
    }

    override fun pageListRequest(chapter: SChapter): Request {
        val slug = chapter.url.removeSuffix("/").substringAfterLast("/")
        return GET("$apiUrl/chapters/see/$slug", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val result = json.decodeFromString<PayloadPageDto>(response.body.string())
        return result.data.images.filter { it.isNotBlank() }
            .mapIndexed { i, img -> Page(i, imageUrl = img) }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val showAllRawsPref = SwitchPreferenceCompat(screen.context).apply {
            key = SHOW_ALL_RAWS_PREF
            title = SHOW_ALL_RAWS_TITLE
            summary = SHOW_ALL_RAWS_SUMMARY
            setDefaultValue(SHOW_ALL_RAWS_DEFAULT)
        }

        screen.addPreference(showAllRawsPref)
    }

    private fun SharedPreferences.showAllRawsPref() = getBoolean(SHOW_ALL_RAWS_PREF, SHOW_ALL_RAWS_DEFAULT)

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()

    companion object {
        private const val SHOW_ALL_RAWS_PREF = "pref_show_all_raws_"
        private const val SHOW_ALL_RAWS_TITLE = "Mostrar todos los capítulos \"Raw\""
        private const val SHOW_ALL_RAWS_SUMMARY = "Mostrar todos los capítulos \"Raw\" en la lista de capítulos, a pesar de que ya exista una versión en español."
        private const val SHOW_ALL_RAWS_DEFAULT = false
    }
}
