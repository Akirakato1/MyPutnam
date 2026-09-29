package com.putnamworkbook.offline.notebook.storage

import com.putnamworkbook.offline.domain.model.PaperStyle
import com.putnamworkbook.offline.domain.model.ProblemKey
import com.putnamworkbook.offline.notebook.model.NotebookDocument
import com.putnamworkbook.offline.notebook.model.NotebookPage
import com.putnamworkbook.offline.notebook.model.StrokeReference
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

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
            }
        }
        bytes.toByteArray()
    }

    fun decode(bytes: ByteArray): NotebookDocument = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        if (input.readInt() != MAGIC) throw IOException("Invalid notebook manifest signature")
        val schemaVersion = input.readInt()
        if (schemaVersion != NotebookDocument.SCHEMA_VERSION) {
            throw IOException("Unsupported notebook schema version: $schemaVersion")
        }
        val key = ProblemKey(input.readInt(), input.readUTF())
        val pageCount = input.readInt()
        if (pageCount !in 1..MAX_PAGES) throw IOException("Invalid notebook page count: $pageCount")
        val pages = List(pageCount) {
            val id = input.readUTF()
            val paperStyle = enumValue<PaperStyle>(input.readUTF(), "paper style")
            val strokeCount = input.readInt()
            if (strokeCount !in 0..MAX_STROKES_PER_PAGE) {
                throw IOException("Invalid page stroke count: $strokeCount")
            }
            NotebookPage(
                id = id,
                paperStyle = paperStyle,
                strokes = List(strokeCount) { StrokeReference(input.readUTF(), input.readBoolean()) },
            )
        }
        if (input.available() != 0) throw IOException("Notebook manifest contains trailing data")
        NotebookDocument(schemaVersion, key, pages)
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String, label: String): T =
        try {
            enumValueOf(value)
        } catch (error: IllegalArgumentException) {
            throw IOException("Invalid $label: $value", error)
        }

    private const val MAGIC = 0x50574E42
    private const val MAX_PAGES = 10_000
    private const val MAX_STROKES_PER_PAGE = 1_000_000
}
