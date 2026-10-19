package com.putnamworkbook.offline.data.library.catalog

import com.putnamworkbook.offline.data.json.JsonValue
import com.putnamworkbook.offline.data.json.StrictJsonCodec
import com.putnamworkbook.offline.data.json.StrictJsonException
import com.putnamworkbook.offline.data.json.asObject
import com.putnamworkbook.offline.data.json.optionalString
import com.putnamworkbook.offline.data.json.requireOnly
import com.putnamworkbook.offline.data.json.requiredLong
import com.putnamworkbook.offline.data.library.storage.AtomicBytesStore
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FileCatalogStore(
    private val root: Path,
    private val embeddedCatalog: () -> ByteArray,
    private val codec: CatalogCodec,
    private val bytesStore: AtomicBytesStore = AtomicBytesStore(),
) : CatalogStore {
    private val mutableState = MutableStateFlow(CatalogState())
    override val state: StateFlow<CatalogState> = mutableState.asStateFlow()

    override suspend fun initialize() {
        Files.createDirectories(root)
        bytesStore.removeStaleTemporaryFiles(root)
        val embedded = codec.decode(embeddedCatalog())
        val cachePath = root.resolve(REMOTE_CATALOG_FILE)
        if (!Files.isRegularFile(cachePath)) {
            mutableState.value = CatalogState(
                catalog = embedded,
                embeddedCatalog = embedded,
                source = CatalogSource.EMBEDDED,
            )
            return
        }
        try {
            val cached = codec.decode(Files.readAllBytes(cachePath))
            val metadata = readMetadata()
            mutableState.value = CatalogState(
                catalog = cached,
                embeddedCatalog = embedded,
                source = CatalogSource.REMOTE_CACHE,
                validators = metadata.validators,
                lastSuccessfulRefreshAt = metadata.lastSuccessfulRefreshAt,
                lastAttemptedRefreshAt = metadata.lastAttemptedRefreshAt,
                lastError = metadata.lastError,
            )
        } catch (error: Exception) {
            mutableState.value = CatalogState(
                catalog = embedded,
                embeddedCatalog = embedded,
                source = CatalogSource.EMBEDDED,
                lastError = "Cached catalog was invalid: ${error.safeMessage()}",
            )
        }
    }

    override suspend fun commitRemote(bytes: ByteArray, validators: CatalogValidators, refreshedAt: Long) {
        val decoded = codec.decode(bytes)
        bytesStore.write(root.resolve(REMOTE_CATALOG_FILE), bytes)
        val next = CatalogState(
            catalog = decoded,
            embeddedCatalog = requireInitialized().embeddedCatalog,
            source = CatalogSource.REMOTE_CACHE,
            validators = validators.sanitized(),
            lastSuccessfulRefreshAt = refreshedAt,
            lastAttemptedRefreshAt = refreshedAt,
            lastError = null,
        )
        writeMetadata(next)
        mutableState.value = next
    }

    override suspend fun recordNotModified(validators: CatalogValidators, refreshedAt: Long) {
        val current = requireInitialized()
        val next = current.copy(
            validators = CatalogValidators(
                etag = validators.etag ?: current.validators.etag,
                lastModified = validators.lastModified ?: current.validators.lastModified,
            ).sanitized(),
            lastSuccessfulRefreshAt = refreshedAt,
            lastAttemptedRefreshAt = refreshedAt,
            lastError = null,
        )
        writeMetadata(next)
        mutableState.value = next
    }

    override suspend fun recordFailure(message: String, attemptedAt: Long) {
        val current = requireInitialized()
        val next = current.copy(lastAttemptedRefreshAt = attemptedAt, lastError = message.take(MAX_ERROR_CHARS))
        writeMetadata(next)
        mutableState.value = next
    }

    private fun requireInitialized(): CatalogState = mutableState.value.also {
        check(it.catalog != null) { "CatalogStore must be initialized first" }
    }

    private fun readMetadata(): Metadata {
        val path = root.resolve(METADATA_FILE)
        if (!Files.isRegularFile(path)) return Metadata()
        return try {
            val row = StrictJsonCodec.parse(Files.readString(path)).asObject("catalog metadata")
            row.requireOnly("etag", "lastModified", "lastSuccessfulRefreshAt", "lastAttemptedRefreshAt", "lastError")
            Metadata(
                validators = CatalogValidators(row.optionalString("etag"), row.optionalString("lastModified")).sanitized(),
                lastSuccessfulRefreshAt = row.optionalLong("lastSuccessfulRefreshAt"),
                lastAttemptedRefreshAt = row.optionalLong("lastAttemptedRefreshAt"),
                lastError = row.optionalString("lastError")?.take(MAX_ERROR_CHARS),
            )
        } catch (_: StrictJsonException) {
            Metadata()
        }
    }

    private fun writeMetadata(state: CatalogState) {
        val value = JsonValue.ObjectValue(linkedMapOf(
            "etag" to state.validators.etag.jsonStringOrNull(),
            "lastModified" to state.validators.lastModified.jsonStringOrNull(),
            "lastSuccessfulRefreshAt" to state.lastSuccessfulRefreshAt.jsonNumberOrNull(),
            "lastAttemptedRefreshAt" to state.lastAttemptedRefreshAt.jsonNumberOrNull(),
            "lastError" to state.lastError.jsonStringOrNull(),
        ))
        bytesStore.write(root.resolve(METADATA_FILE), StrictJsonCodec.stringify(value).encodeToByteArray())
    }

    private fun CatalogValidators.sanitized() = CatalogValidators(
        etag = etag?.take(MAX_VALIDATOR_CHARS),
        lastModified = lastModified?.take(MAX_VALIDATOR_CHARS),
    )

    private fun String?.jsonStringOrNull(): JsonValue = this?.let(JsonValue::StringValue) ?: JsonValue.NullValue
    private fun Long?.jsonNumberOrNull(): JsonValue = this?.let { JsonValue.NumberValue(it.toString()) } ?: JsonValue.NullValue

    private data class Metadata(
        val validators: CatalogValidators = CatalogValidators(null, null),
        val lastSuccessfulRefreshAt: Long? = null,
        val lastAttemptedRefreshAt: Long? = null,
        val lastError: String? = null,
    )

    companion object {
        const val REMOTE_CATALOG_FILE = "catalog.remote.json"
        const val METADATA_FILE = "catalog.metadata.json"
        private const val MAX_VALIDATOR_CHARS = 1_024
        private const val MAX_ERROR_CHARS = 2_048
    }
}

private fun Map<String, JsonValue>.optionalLong(key: String): Long? = when (val value = get(key)) {
    null, JsonValue.NullValue -> null
    else -> value.let { (it as? JsonValue.NumberValue)?.value?.toLongOrNull() }
        ?: throw StrictJsonException("Expected $key to be an integer")
}

private fun Throwable.safeMessage(): String = message?.take(256) ?: javaClass.simpleName
