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
    private val key = ProblemKey("course", "chapter-1", "P1")

    @Test
    fun versionThreeRoundTripsCanonicalKeyEditHistoryAndCursor() {
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

        assertEquals(3, decoded.sourceVersion)
        assertEquals(document, decoded.document)
    }

    @Test
    fun versionsOneAndTwoAreRejectedWithoutLegacyDecodeBranches() {
        listOf(1, 2).forEach { version ->
            assertThrows(IOException::class.java) {
                NotebookManifestCodec.decode(legacyHeader(version))
            }
        }
    }

    @Test
    fun invalidHistoryCursorIsRejected() {
        val bytes = versionThreeManifest(
            appliedEditCount = 2,
            activated = listOf("stroke"),
            deactivated = emptyList(),
        )

        assertThrows(IOException::class.java) { NotebookManifestCodec.decode(bytes) }
    }

    @Test
    fun editGroupCannotActivateAndDeactivateTheSameStroke() {
        val bytes = versionThreeManifest(
            appliedEditCount = 1,
            activated = listOf("stroke"),
            deactivated = listOf("stroke"),
        )

        assertThrows(IOException::class.java) { NotebookManifestCodec.decode(bytes) }
    }

    @Test
    fun trailingDataIsRejected() {
        val bytes = versionThreeManifest(
            appliedEditCount = 1,
            activated = listOf("stroke"),
            deactivated = emptyList(),
        ) + byteArrayOf(42)

        assertThrows(IOException::class.java) { NotebookManifestCodec.decode(bytes) }
    }

    private fun legacyHeader(version: Int): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(version)
            }
            bytes.toByteArray()
        }

    private fun versionThreeManifest(
        appliedEditCount: Int,
        activated: List<String>,
        deactivated: List<String>,
    ): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(MAGIC)
            output.writeInt(3)
            output.writeUTF(key.canonical)
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
