package com.putnamworkbook.offline.notebook.storage

import com.putnamworkbook.offline.domain.model.PaperStyle
import com.putnamworkbook.offline.domain.model.ProblemKey
import com.putnamworkbook.offline.notebook.model.NotebookDocument
import com.putnamworkbook.offline.notebook.model.NotebookEditGroup
import com.putnamworkbook.offline.notebook.model.NotebookPage
import com.putnamworkbook.offline.notebook.model.StrokeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException

class NotebookManifestCodecTest {
    private val key = ProblemKey(2025, "A1")

    @Test
    fun versionTwoRoundTripsEditHistoryAndCursor() {
        val document = NotebookDocument(
            key = key,
            pages = listOf(
                NotebookPage(
                    id = "page-one",
                    paperStyle = PaperStyle.SQUARE_GRID,
                    strokes = listOf(
                        StrokeReference("old", active = false),
                        StrokeReference("replacement", active = true),
                    ),
                    editHistory = listOf(
                        NotebookEditGroup("draw", listOf("old"), emptyList()),
                        NotebookEditGroup("move", listOf("replacement"), listOf("old")),
                    ),
                    appliedEditCount = 2,
                ),
            ),
        )

        val decoded = NotebookManifestCodec.decode(NotebookManifestCodec.encode(document))

        assertEquals(2, decoded.sourceVersion)
        assertEquals(document, decoded.document)
    }

    @Test
    fun versionOneMigratesOnlyActiveStrokesIntoOneInitialHistoryState() {
        val decoded = NotebookManifestCodec.decode(
            versionOneManifest(
                listOf(
                    "visible" to true,
                    "legacy-undone" to false,
                    "also-visible" to true,
                ),
            ),
        )

        assertEquals(1, decoded.sourceVersion)
        assertEquals(NotebookDocument.SCHEMA_VERSION, decoded.document.schemaVersion)
        val page = decoded.document.pages.single()
        assertEquals(listOf("visible", "also-visible"), page.strokes.map { it.strokeId })
        assertEquals(listOf(true, true), page.strokes.map { it.active })
        assertEquals(1, page.editHistory.size)
        assertEquals(listOf("visible", "also-visible"), page.editHistory.single().activatedStrokeIds)
        assertEquals(emptyList<String>(), page.editHistory.single().deactivatedStrokeIds)
        assertEquals(1, page.appliedEditCount)
    }

    @Test
    fun invalidHistoryCursorIsRejected() {
        val bytes = versionTwoManifest(
            appliedEditCount = 2,
            activated = listOf("stroke"),
            deactivated = emptyList(),
        )

        assertThrows(IOException::class.java) { NotebookManifestCodec.decode(bytes) }
    }

    @Test
    fun editGroupCannotActivateAndDeactivateTheSameStroke() {
        val bytes = versionTwoManifest(
            appliedEditCount = 1,
            activated = listOf("stroke"),
            deactivated = listOf("stroke"),
        )

        assertThrows(IOException::class.java) { NotebookManifestCodec.decode(bytes) }
    }

    @Test
    fun trailingDataIsRejected() {
        val bytes = versionTwoManifest(
            appliedEditCount = 1,
            activated = listOf("stroke"),
            deactivated = emptyList(),
        ) + byteArrayOf(42)

        assertThrows(IOException::class.java) { NotebookManifestCodec.decode(bytes) }
    }

    private fun versionOneManifest(strokes: List<Pair<String, Boolean>>): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(1)
                output.writeInt(key.year)
                output.writeUTF(key.code)
                output.writeInt(1)
                output.writeUTF("page-one")
                output.writeUTF(PaperStyle.BLANK.name)
                output.writeInt(strokes.size)
                strokes.forEach { (id, active) ->
                    output.writeUTF(id)
                    output.writeBoolean(active)
                }
            }
            bytes.toByteArray()
        }

    private fun versionTwoManifest(
        appliedEditCount: Int,
        activated: List<String>,
        deactivated: List<String>,
    ): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(MAGIC)
            output.writeInt(2)
            output.writeInt(key.year)
            output.writeUTF(key.code)
            output.writeInt(1)
            output.writeUTF("page-one")
            output.writeUTF(PaperStyle.BLANK.name)
            output.writeInt(1)
            output.writeUTF("stroke")
            output.writeBoolean(true)
            output.writeInt(1)
            output.writeUTF("edit")
            output.writeInt(activated.size)
            activated.forEach(output::writeUTF)
            output.writeInt(deactivated.size)
            deactivated.forEach(output::writeUTF)
            output.writeInt(appliedEditCount)
        }
        bytes.toByteArray()
    }

    private companion object {
        const val MAGIC = 0x50574E42
    }
}
