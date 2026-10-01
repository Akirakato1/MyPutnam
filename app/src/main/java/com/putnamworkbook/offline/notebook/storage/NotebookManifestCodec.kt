package com.putnamworkbook.offline.notebook.storage

import com.putnamworkbook.offline.domain.model.PaperStyle
import com.putnamworkbook.offline.domain.model.ProblemKey
import com.putnamworkbook.offline.notebook.model.NotebookDocument
import com.putnamworkbook.offline.notebook.model.NotebookEditGroup
import com.putnamworkbook.offline.notebook.model.NotebookPage
import com.putnamworkbook.offline.notebook.model.StrokeReference
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

internal data class DecodedNotebookManifest(
    val sourceVersion: Int,
    val document: NotebookDocument,
)

internal object NotebookManifestCodec {
    fun encode(document: NotebookDocument): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(MAGIC)
            output.writeInt(document.schemaVersion)
            output.writeInt(document.key.year)
            output.writeUTF(document.key.code)
            output.writeInt(document.pages.size)
            document.pages.forEach { page ->
                output.writeUTF(page.id)
                output.writeUTF(page.paperStyle.name)
                output.writeInt(page.strokes.size)
                page.strokes.forEach { reference ->
                    output.writeUTF(reference.strokeId)
                    output.writeBoolean(reference.active)
                }
                output.writeInt(page.editHistory.size)
                page.editHistory.forEach { group ->
                    output.writeUTF(group.id)
                    writeIds(output, group.activatedStrokeIds)
                    writeIds(output, group.deactivatedStrokeIds)
                }
                output.writeInt(page.appliedEditCount)
            }
        }
        bytes.toByteArray()
    }

    fun decode(bytes: ByteArray): DecodedNotebookManifest =
        try {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                if (input.readInt() != MAGIC) throw IOException("Invalid notebook manifest signature")
                val sourceVersion = input.readInt()
                if (sourceVersion !in SUPPORTED_VERSIONS) {
                    throw IOException("Unsupported notebook schema version: $sourceVersion")
                }
                val key = ProblemKey(input.readInt(), input.readUTF())
                val pageCount = input.checkedCount("notebook page", MAX_PAGES, minimum = 1)
                val pages = List(pageCount) {
                    if (sourceVersion == LEGACY_VERSION) decodeLegacyPage(input) else decodeCurrentPage(input)
                }
                if (input.available() != 0) throw IOException("Notebook manifest contains trailing data")
                DecodedNotebookManifest(
                    sourceVersion = sourceVersion,
                    document = NotebookDocument(key = key, pages = pages),
                )
            }
        } catch (error: IOException) {
            throw error
        } catch (error: RuntimeException) {
            throw IOException("Invalid notebook manifest", error)
        }

    private fun decodeLegacyPage(input: DataInputStream): NotebookPage {
        val id = input.readUTF()
        val paperStyle = enumValue<PaperStyle>(input.readUTF(), "paper style")
        val strokeCount = input.checkedCount("page stroke", MAX_STROKES_PER_PAGE)
        val activeStrokes = List(strokeCount) {
            StrokeReference(input.readUTF(), input.readBoolean())
        }.filter { it.active }
        val history = if (activeStrokes.isEmpty()) {
            emptyList()
        } else {
            listOf(
                NotebookEditGroup(
                    id = LEGACY_EDIT_ID,
                    activatedStrokeIds = activeStrokes.map { it.strokeId },
                    deactivatedStrokeIds = emptyList(),
                ),
            )
        }
        return NotebookPage(
            id = id,
            paperStyle = paperStyle,
            strokes = activeStrokes,
            editHistory = history,
            appliedEditCount = history.size,
        )
    }

    private fun decodeCurrentPage(input: DataInputStream): NotebookPage {
        val id = input.readUTF()
        val paperStyle = enumValue<PaperStyle>(input.readUTF(), "paper style")
        val strokeCount = input.checkedCount("page stroke", MAX_STROKES_PER_PAGE)
        val strokes = List(strokeCount) { StrokeReference(input.readUTF(), input.readBoolean()) }
        val editCount = input.checkedCount("edit history", MAX_EDITS_PER_PAGE)
        val history = List(editCount) {
            NotebookEditGroup(
                id = input.readUTF(),
                activatedStrokeIds = input.readIds("activated stroke"),
                deactivatedStrokeIds = input.readIds("deactivated stroke"),
            )
        }
        val appliedEditCount = input.readInt()
        return NotebookPage(id, paperStyle, strokes, history, appliedEditCount)
    }

    private fun DataInputStream.readIds(label: String): List<String> {
        val count = checkedCount(label, MAX_STROKES_PER_PAGE)
        return List(count) { readUTF() }
    }

    private fun DataInputStream.checkedCount(label: String, maximum: Int, minimum: Int = 0): Int =
        readInt().also { count ->
            if (count !in minimum..maximum) throw IOException("Invalid $label count: $count")
        }

    private fun writeIds(output: DataOutputStream, ids: List<String>) {
        output.writeInt(ids.size)
        ids.forEach(output::writeUTF)
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String, label: String): T =
        try {
            enumValueOf(value)
        } catch (error: IllegalArgumentException) {
            throw IOException("Invalid $label: $value", error)
        }

    private const val MAGIC = 0x50574E42
    private const val LEGACY_VERSION = 1
    private const val LEGACY_EDIT_ID = "legacy-initial"
    private val SUPPORTED_VERSIONS = setOf(LEGACY_VERSION, NotebookDocument.SCHEMA_VERSION)
    private const val MAX_PAGES = 10_000
    private const val MAX_STROKES_PER_PAGE = 1_000_000
    private const val MAX_EDITS_PER_PAGE = 1_000_000
}
