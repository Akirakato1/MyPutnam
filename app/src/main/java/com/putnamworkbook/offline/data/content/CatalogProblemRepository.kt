package com.putnamworkbook.offline.data.content

import com.putnamworkbook.offline.data.json.asObject
import com.putnamworkbook.offline.data.json.requiredArray
import com.putnamworkbook.offline.data.json.requiredInt
import com.putnamworkbook.offline.data.json.requiredString
import com.putnamworkbook.offline.data.json.StrictJsonCodec
import com.putnamworkbook.offline.data.library.catalog.CatalogContentType
import com.putnamworkbook.offline.data.library.catalog.PublicationCatalog
import com.putnamworkbook.offline.data.library.index.ContentAvailability
import com.putnamworkbook.offline.data.library.index.ContentSource
import com.putnamworkbook.offline.data.library.index.InstallError
import com.putnamworkbook.offline.data.library.index.InstallErrorCode
import com.putnamworkbook.offline.data.library.index.InstalledContentIndex
import com.putnamworkbook.offline.data.library.index.InstalledContentSnapshot
import com.putnamworkbook.offline.data.library.index.ResolvedInstalledContent
import com.putnamworkbook.offline.data.library.packages.PackageEnvelope
import com.putnamworkbook.offline.data.library.packages.PackageEnvelopeCodec
import com.putnamworkbook.offline.data.library.packages.PackageMemberDeclaration
import com.putnamworkbook.offline.data.library.packages.PackageStore
import com.putnamworkbook.offline.data.library.packages.sha256
import com.putnamworkbook.offline.domain.model.HintStage
import com.putnamworkbook.offline.domain.model.ProblemKey
import com.putnamworkbook.offline.domain.model.ProblemSetKey
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

interface InstalledPackageReader {
    suspend fun envelope(item: ResolvedInstalledContent): PackageEnvelope
    suspend fun read(item: ResolvedInstalledContent, declaration: PackageMemberDeclaration): ByteArray
    suspend fun quarantine(item: ResolvedInstalledContent, message: String)
}

class FileInstalledPackageReader(
    packagesRoot: Path,
    private val packageStore: PackageStore,
    private val contentIndex: InstalledContentIndex,
    private val envelopeCodec: PackageEnvelopeCodec = PackageEnvelopeCodec(),
) : InstalledPackageReader {
    private val packagesRoot = packagesRoot.toAbsolutePath().normalize()

    override suspend fun envelope(item: ResolvedInstalledContent): PackageEnvelope {
        val bytes = readPath(item, "package.json", expectedBytes = null, expectedSha256 = null)
        val envelope = envelopeCodec.decode(bytes)
        val activePackage = when (item.activeSource) {
            ContentSource.BUNDLED -> item.bundled
            ContentSource.DOWNLOADED -> item.downloaded
            null -> null
        } ?: integrity(item, "Content has no active package")
        if (envelope.contentKey != item.metadata.key || envelope.packageRevision != activePackage.descriptor.packageRevision) {
            integrity(item, "Active package envelope does not match its index")
        }
        return envelope
    }

    override suspend fun read(item: ResolvedInstalledContent, declaration: PackageMemberDeclaration): ByteArray =
        readPath(item, declaration.path, declaration.bytes, declaration.sha256)

    override suspend fun quarantine(item: ResolvedInstalledContent, message: String) {
        item.activeDigest?.let { digest ->
            contentIndex.quarantine(item.metadata.key, digest, InstallError(InstallErrorCode.INTEGRITY, message))
        }
    }

    private suspend fun readPath(
        item: ResolvedInstalledContent,
        memberPath: String,
        expectedBytes: Long?,
        expectedSha256: String?,
    ): ByteArray {
        val root = activeRoot(item)
        val target = root.resolve(memberPath).normalize()
        if (!target.startsWith(root) || !Files.isRegularFile(target)) integrity(item, "Package member is missing: $memberPath")
        if (expectedBytes != null && Files.size(target) != expectedBytes) integrity(item, "Package member size changed: $memberPath")
        val bytes = Files.readAllBytes(target)
        if (expectedSha256 != null && sha256(bytes) != expectedSha256) integrity(item, "Package member hash changed: $memberPath")
        return bytes
    }

    private suspend fun activeRoot(item: ResolvedInstalledContent): Path {
        val digest = item.activeDigest ?: integrity(item, "Content is unavailable")
        val resolved = when (item.activeSource) {
            ContentSource.BUNDLED -> {
                val bundled = item.bundled ?: integrity(item, "Bundled package metadata is missing")
                packageStore.ensureBundledExtracted(item.metadata.key, bundled.descriptor).root
            }
            ContentSource.DOWNLOADED -> item.activeLocation?.let(Paths::get)
                ?: integrity(item, "Downloaded package location is missing")
            null -> integrity(item, "Content is unavailable")
        }.toAbsolutePath().normalize()
        if (resolved.parent != packagesRoot || resolved.fileName.toString() != digest) {
            integrity(item, "Active package location is invalid")
        }
        return resolved
    }

    private suspend fun integrity(item: ResolvedInstalledContent, message: String): Nothing {
        quarantine(item, message)
        throw ContentIntegrityException(message)
    }
}

class ContentIntegrityException(message: String) : Exception(message)

class CatalogProblemRepository(
    private val catalog: () -> PublicationCatalog?,
    private val snapshots: Flow<InstalledContentSnapshot>,
    private val packageReader: InstalledPackageReader,
) : ContentRepository {
    override fun observeInstalledRevision(): Flow<Long> = snapshots.map { it.revision }.distinctUntilChanged()

    override suspend fun contentVersion(): String = catalog()?.catalogRevision ?: "unknown"

    override suspend fun collections(): List<LibraryCollection> {
        val items = installedProblems()
        val ids = items.mapNotNullTo(linkedSetOf()) { it.metadata.collectionId }
        val catalogCollections = catalog()?.collections.orEmpty().associateBy { it.id }
        return ids.map { id ->
            val row = catalogCollections[id]
            LibraryCollection(id, row?.title ?: id, row?.kind ?: "competition", row?.displayOrder ?: Int.MAX_VALUE)
        }.sortedWith(compareBy(LibraryCollection::displayOrder, LibraryCollection::title))
    }

    override suspend fun sets(collectionId: String): List<ProblemSetSummary> = installedProblems()
        .filter { it.metadata.collectionId == collectionId }
        .mapNotNull { it.metadata.setId }
        .distinct()
        .map { id -> ProblemSetSummary(ProblemSetKey(collectionId, id), id, id.toIntOrNull() ?: Int.MAX_VALUE, id.toIntOrNull()) }
        .sortedBy(ProblemSetSummary::displayOrder)

    override suspend fun topics(): List<TopicSummary> {
        val used = installedProblems().flatMapTo(linkedSetOf()) { it.metadata.topics }
        val known = catalog()?.topics.orEmpty().associateBy { it.id }
        return used.map { id -> known[id]?.let { TopicSummary(it.id, it.label, it.displayOrder) } ?: TopicSummary(id, id, Int.MAX_VALUE) }
            .sortedWith(compareBy(TopicSummary::displayOrder, TopicSummary::label))
    }

    override suspend fun problems(setKey: ProblemSetKey): List<ProblemSummary> = installedProblems()
        .filter { it.metadata.collectionId == setKey.collectionId && it.metadata.setId == setKey.setId }
        .map(::summary)
        .sortedBy(ProblemSummary::ordinal)

    override suspend fun problemsByTopic(topicId: String): List<ProblemSummary> = installedProblems()
        .filter { topicId in it.metadata.topics }
        .map(::summary)
        .sortedWith(compareBy({ it.competitionYear ?: Int.MIN_VALUE }, ProblemSummary::ordinal))

    override suspend fun problem(key: ProblemKey): ProblemContent? {
        val item = installedProblems().firstOrNull { it.metadata.key == key.canonical } ?: return null
        val envelope = packageReader.envelope(item)
        val declarations = envelope.members.associateBy(PackageMemberDeclaration::path)
        suspend fun reject(message: String): Nothing {
            packageReader.quarantine(item, message)
            throw ContentIntegrityException(message)
        }
        val sourceDeclaration = declarations["source/problem.json"] ?: reject("Problem source is missing")
        val root = try {
            StrictJsonCodec.parse(packageReader.read(item, sourceDeclaration).decodeToString()).asObject("problem")
        } catch (error: Exception) {
            reject("Problem source is invalid: ${error.message?.take(160)}")
        }
        if (root.requiredInt("schemaVersion") != 3 || root.requiredString("collectionId") != key.collectionId ||
            root.requiredString("setId") != key.setId || root.requiredString("problemId") != key.problemId
        ) reject("Problem source identity does not match its package")

        val requiredPaths = buildList {
            add("documents/statement.fragment.html")
            addAll((1..4).map { "documents/hint-$it.fragment.html" })
            addAll(root.requiredArray("prerequisites").indices.map { "documents/prerequisite-${it + 1}.fragment.html" })
            add("documents/solution.fragment.html")
        }
        requiredPaths.firstOrNull { it !in declarations }?.let { reject("Declared document is missing: $it") }

        fun resource(path: String): ContentResourceRef {
            val declaration = checkNotNull(declarations[path])
            return ContentResourceRef(item.metadata.key, item.activeDigest!!, path, declaration.sha256)
        }
        val hints = root.requiredArray("hints").mapIndexed { index, value ->
            val row = value.asObject("hint")
            HintContent(
                row.requiredInt("order"),
                HintStage.fromStorageValue(row.requiredString("stage")),
                row.requiredString("title"),
                resource("documents/hint-${index + 1}.fragment.html"),
            )
        }
        val prerequisites = root.requiredArray("prerequisites").mapIndexed { index, value ->
            val row = value.asObject("prerequisite")
            ContentDocument(row.requiredInt("order"), row.requiredString("title"), resource("documents/prerequisite-${index + 1}.fragment.html"))
        }
        val attribution = root.requiredArray("sources").map { value ->
            val row = value.asObject("source")
            Attribution(row.requiredString("kind"), row.requiredString("label"), row.requiredString("url"))
        }
        return ProblemContent(
            summary = summary(item),
            statement = resource("documents/statement.fragment.html"),
            hints = hints,
            prerequisites = prerequisites,
            solution = resource("documents/solution.fragment.html"),
            attribution = attribution,
        )
    }

    private suspend fun installedProblems(): List<ResolvedInstalledContent> = snapshots.first().items.filter {
        it.availability == ContentAvailability.INSTALLED && it.metadata.contentType == CatalogContentType.PROBLEM
    }

    private fun summary(item: ResolvedInstalledContent): ProblemSummary {
        val metadata = item.metadata
        val collectionTitle = catalog()?.collections?.firstOrNull { it.id == metadata.collectionId }?.title ?: metadata.collectionId.orEmpty()
        return ProblemSummary(
            ProblemKey.parse(metadata.key), metadata.label, metadata.displayOrder,
            collectionTitle, metadata.setId.orEmpty(), metadata.setId?.toIntOrNull(),
        )
    }
}
