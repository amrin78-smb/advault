package com.advault.filtering

import com.advault.core.common.FilterCategory
import com.advault.core.common.UpdateFrequency
import com.advault.data.models.AdProvider
import com.advault.data.models.ProviderDatabaseInfo
import com.advault.data.models.ProviderMatch
import com.advault.data.repository.ProviderRepository
import com.advault.filtering.data.BlocklistDownloader
import com.advault.filtering.data.BlocklistUpdateScheduler
import com.advault.filtering.data.FetchResult
import com.advault.filtering.data.FilteringSettings
import com.advault.filtering.data.UpdateSchedule
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList

class FakeDownloader : BlocklistDownloader {
    sealed interface Response {
        data class Body(val text: String, val etag: String? = null, val lastModified: String? = null) : Response
        data object NotModified : Response
        data class Error(val message: String) : Response
    }

    data class Request(val url: String, val etag: String?, val lastModified: String?)

    val responses = HashMap<String, Response>()
    val requests = CopyOnWriteArrayList<Request>()

    override fun <T> fetch(url: String, etag: String?, lastModified: String?, consume: (InputStream) -> T): FetchResult<T> {
        requests += Request(url, etag, lastModified)
        return when (val r = responses[url] ?: Response.Error("HTTP 404")) {
            is Response.Body -> FetchResult.Fetched(consume(r.text.byteInputStream()), r.etag, r.lastModified)
            Response.NotModified -> FetchResult.NotModified
            is Response.Error -> throw IOException(r.message)
        }
    }
}

class FakeSettings : FilteringSettings {
    override val enabledCategories = MutableStateFlow(FilterCategory.entries.toSet())
    override val updateSchedule = MutableStateFlow(UpdateSchedule(UpdateFrequency.DAILY, false))
}

class FakeScheduler : BlocklistUpdateScheduler {
    val applied = CopyOnWriteArrayList<UpdateSchedule>()
    override fun apply(schedule: UpdateSchedule) { applied += schedule }
}

class FakeProviderRepository : ProviderRepository {
    override val providers = MutableStateFlow<List<AdProvider>>(emptyList())
    override val info = MutableStateFlow<ProviderDatabaseInfo?>(null)
    override fun match(domain: String): ProviderMatch? = null
    override suspend fun load() {}
    override suspend fun update(): Result<ProviderDatabaseInfo> = Result.failure(NotImplementedError())
}
