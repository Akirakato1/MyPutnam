package com.putnamworkbook.offline.export

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.putnamworkbook.offline.data.content.ContentRepository
import com.putnamworkbook.offline.data.content.HintContent
import com.putnamworkbook.offline.data.content.ProblemContent
import com.putnamworkbook.offline.data.content.ProblemSummary
import com.putnamworkbook.offline.data.user.RoomProgressRepository
import com.putnamworkbook.offline.data.user.UserDatabase
import com.putnamworkbook.offline.domain.model.BrushSpec
import com.putnamworkbook.offline.domain.model.PaperStyle
import com.putnamworkbook.offline.domain.model.ProblemKey
import com.putnamworkbook.offline.domain.model.HintStage
import com.putnamworkbook.offline.notebook.model.StrokeRecord
import com.putnamworkbook.offline.notebook.model.StrokeSample
import com.putnamworkbook.offline.notebook.storage.FileNotebookRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WorkbookPdfExporterTest {
    private val key = ProblemKey(2025, "A1")
    private lateinit var database: UserDatabase
    private lateinit var testRoot: Path
    private lateinit var notebookRoot: Path

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(context, UserDatabase::class.java).build()
        testRoot = context.cacheDir.toPath().resolve("pdf-export-${UUID.randomUUID()}")
        notebookRoot = testRoot.resolve("notebooks")
        Files.createDirectories(testRoot)
    }

    @After
    fun tearDown() {
        database.close()
        deleteTree(testRoot)
    }

    @Test
    fun pdfOpensWithOrderedPagesVectorInkAndOnlyRevealedHelp() = runBlocking {
        val progress = RoomProgressRepository(database, clock = { 100L })
        val notebooks = FileNotebookRepository(notebookRoot)
        val content = FakeContentRepository(problemContent())
        val rasterizer = RecordingRasterizer()
        val exporter = WorkbookPdfExporter(content, progress, notebooks, rasterizer)

        progress.markOpened(key)
        progress.revealNextHint(key)
        val first = notebooks.loadOrCreate(key, PaperStyle.BLANK).pages.single()
        notebooks.commitStroke(
            key,
            first.id,
            StrokeRecord(
                id = "diagonal",
                brush = BrushSpec(colorArgb = 0xFF111827, width = 12f),
                samples = listOf(
                    StrokeSample(0f, 0f, 0L, null),
                    StrokeSample(0.5f, 0.5f, 10L, null),
                    StrokeSample(1f, 1f, 20L, null),
                ),
            ),
        )
        notebooks.addPage(key, first.id, PaperStyle.SQUARE_GRID)

        val output = ByteArrayOutputStream()
        val result = exporter.export(
            key,
            WorkbookExportOptions(includeRevealedHints = false, includeRevealedSolution = false),
            output,
        )
        assertTrue(result is ExportResult.Success)
        assertEquals(listOf("statement.html"), rasterizer.requestedPaths)

        val pdfFile = testRoot.resolve("workbook.pdf")
        Files.write(pdfFile, output.toByteArray())
        ParcelFileDescriptor.open(pdfFile.toFile(), ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                assertEquals(3, renderer.pageCount)
                val reference = renderer.renderPage(0)
                val blank = renderer.renderPage(1)
                val grid = renderer.renderPage(2)
                assertTrue("Year/code header was not drawn", reference.darkPixelCount(topRows = 180) > 100)
                assertTrue("Statement rendering was not drawn", reference.bluePixelCount() > 1_000)
                assertTrue("Grid page did not contain its background", grid.nonWhitePixelCount() > blank.nonWhitePixelCount() * 2)
                assertTrue("Ink start was not drawn", blank.hasDarkPixelNear(86, 130, 18))
                assertTrue("Ink midpoint was not drawn", blank.hasDarkPixelNear(595, 864, 18))
                assertTrue("Ink end was not drawn", blank.hasDarkPixelNear(1_104, 1_598, 18))
                reference.recycle()
                blank.recycle()
                grid.recycle()
            }
        }

        rasterizer.requestedPaths.clear()
        val withHelp = ByteArrayOutputStream()
        exporter.export(key, WorkbookExportOptions(true, true), withHelp)
        assertEquals(listOf("statement.html", "hint-1.html"), rasterizer.requestedPaths)
        progress.revealSolution(key)
        rasterizer.requestedPaths.clear()
        exporter.export(key, WorkbookExportOptions(true, true), ByteArrayOutputStream())
        assertEquals(listOf("statement.html", "hint-1.html", "solution.html"), rasterizer.requestedPaths)

        val progressBeforeFailure = progress.observe(key).first()
        val notebooksBeforeFailure = snapshot(notebookRoot)
        val failure = exporter.export(
            key,
            WorkbookExportOptions(false, false),
            object : OutputStream() {
                override fun write(value: Int) = throw IOException("closed destination")
                override fun write(bytes: ByteArray, offset: Int, length: Int) =
                    throw IOException("closed destination")
            },
        )
        assertTrue(failure is ExportResult.Failure)
        assertEquals(progressBeforeFailure, progress.observe(key).first())
        assertSnapshotEquals(notebooksBeforeFailure, snapshot(notebookRoot))
    }

    @Test
    fun realOfflineHtmlRasterizerProducesPrintResolutionContent() = runBlocking {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var activity: ComponentActivity
            scenario.onActivity { activity = it }
            val consumed = mutableListOf<Bitmap>()
            val sliceNonWhitePixels = mutableListOf<Int>()
            var nonWhitePixels = 0
            var rightEdgeNonWhitePixels = 0
            val sliceCount = withTimeout(10_000) {
                WebViewContentRasterizer(activity, testContext).render(
                    assetPath = "export/math-fixture.html",
                    widthPixels = 1_046,
                    firstSliceHeightPixels = 1_400,
                    continuationSliceHeightPixels = 1_500,
                ) { bitmap ->
                    assertFalse(bitmap.isRecycled)
                    assertEquals(1_046, bitmap.width)
                    val count = bitmap.nonWhitePixelCount()
                    nonWhitePixels += count
                    sliceNonWhitePixels += count
                    rightEdgeNonWhitePixels += bitmap.rightEdgeNonWhitePixelCount()
                    consumed += bitmap
                }
            }

            assertTrue(sliceCount >= 2)
            assertEquals(sliceCount, consumed.size)
            assertTrue(nonWhitePixels > 1_000)
            assertTrue(sliceNonWhitePixels.last() > 100)
            assertEquals(0, rightEdgeNonWhitePixels)
            assertTrue(consumed.all(Bitmap::isRecycled))
        }
    }

    @Test
    fun exportUsesOneNotebookSnapshotWhileEditingContinues() = runBlocking {
        val progress = RoomProgressRepository(database, clock = { 100L })
        val notebooks = FileNotebookRepository(notebookRoot)
        val rasterizer = BlockingRasterizer()
        val exporter = WorkbookPdfExporter(FakeContentRepository(problemContent()), progress, notebooks, rasterizer)

        progress.markOpened(key)
        val first = notebooks.loadOrCreate(key, PaperStyle.BLANK).pages.single()
        val second = notebooks.addPage(key, first.id, PaperStyle.SQUARE_GRID).pages[1]
        notebooks.commitStroke(key, first.id, diagonalStroke("snapshot-stroke"))

        val pending = async {
            exporter.export(key, WorkbookExportOptions(false, false), ByteArrayOutputStream())
        }
        rasterizer.started.await()
        notebooks.deletePage(key, first.id)
        rasterizer.proceed.complete(Unit)

        val result = pending.await()
        assertTrue(result is ExportResult.Success)
        assertEquals(3, (result as ExportResult.Success).pageCount)
        assertEquals(listOf(second.id), notebooks.loadExisting(key)?.pages?.map { it.id })
    }

    @Test
    fun realOfflineHtmlRasterizerRejectsMissingLocalStylesheet() = runBlocking {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var activity: ComponentActivity
            scenario.onActivity { activity = it }

            val failure = runCatching {
                withTimeout(10_000) {
                    WebViewContentRasterizer(activity, testContext).render(
                        assetPath = "export/missing-style-fixture.html",
                        widthPixels = 1_046,
                        firstSliceHeightPixels = 1_400,
                        continuationSliceHeightPixels = 1_500,
                    ) { }
                }
            }.exceptionOrNull()

            assertTrue(failure is IOException)
        }
    }

    private fun problemContent() = ProblemContent(
        summary = ProblemSummary(key, 1),
        statementAssetPath = "statement.html",
        statementSha256 = "statement",
        hints = (1..4).map { index ->
            HintContent(
                index,
                HintStage.entries[index - 1],
                "Hint $index",
                "hint-$index.html",
                "hint-$index",
            )
        },
        prerequisites = emptyList(),
        solutionAssetPath = "solution.html",
        solutionSha256 = "solution",
        attribution = emptyList(),
    )

    private fun diagonalStroke(id: String) = StrokeRecord(
        id = id,
        brush = BrushSpec(colorArgb = 0xFF111827, width = 12f),
        samples = listOf(
            StrokeSample(0f, 0f, 0L, null),
            StrokeSample(1f, 1f, 20L, null),
        ),
    )

    private fun PdfRenderer.renderPage(index: Int): Bitmap {
        val page = openPage(index)
        return Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
        }
    }

    private fun Bitmap.darkPixelCount(topRows: Int): Int = pixelCount { x, y, color ->
        y < topRows && Color.red(color) < 90 && Color.green(color) < 90 && Color.blue(color) < 90
    }

    private fun Bitmap.bluePixelCount(): Int = pixelCount { _, _, color ->
        Color.blue(color) > Color.red(color) + 25 && Color.blue(color) > Color.green(color) + 10
    }

    private fun Bitmap.nonWhitePixelCount(): Int = pixelCount { _, _, color ->
        Color.red(color) < 248 || Color.green(color) < 248 || Color.blue(color) < 248
    }

    private fun Bitmap.rightEdgeNonWhitePixelCount(): Int {
        var count = 0
        for (y in 0 until height step 2) {
            for (x in (width - 16).coerceAtLeast(0) until width) {
                val color = getPixel(x, y)
                if (Color.red(color) < 248 || Color.green(color) < 248 || Color.blue(color) < 248) count += 1
            }
        }
        return count
    }

    private fun Bitmap.hasDarkPixelNear(centerX: Int, centerY: Int, radius: Int): Boolean {
        for (y in (centerY - radius).coerceAtLeast(0)..(centerY + radius).coerceAtMost(height - 1)) {
            for (x in (centerX - radius).coerceAtLeast(0)..(centerX + radius).coerceAtMost(width - 1)) {
                val color = getPixel(x, y)
                if (Color.red(color) < 60 && Color.green(color) < 60 && Color.blue(color) < 60) return true
            }
        }
        return false
    }

    private inline fun Bitmap.pixelCount(predicate: (Int, Int, Int) -> Boolean): Int {
        var count = 0
        for (y in 0 until height step 2) {
            for (x in 0 until width step 2) {
                if (predicate(x, y, getPixel(x, y))) count += 1
            }
        }
        return count
    }

    private fun snapshot(root: Path): Map<String, ByteArray> {
        if (!Files.exists(root)) return emptyMap()
        return Files.walk(root).use { paths ->
            paths.filter(Files::isRegularFile).sorted().iterator().asSequence()
                .associate { path -> root.relativize(path).toString() to Files.readAllBytes(path) }
        }
    }

    private fun assertSnapshotEquals(expected: Map<String, ByteArray>, actual: Map<String, ByteArray>) {
        assertEquals(expected.keys, actual.keys)
        expected.forEach { (path, bytes) -> assertTrue(bytes.contentEquals(actual.getValue(path))) }
    }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
}

private class FakeContentRepository(
    private val content: ProblemContent,
) : ContentRepository {
    override suspend fun years() = listOf(content.summary.key.year)
    override suspend fun problems(year: Int) = listOf(content.summary)
    override suspend fun problem(key: ProblemKey) = content.takeIf { it.summary.key == key }
}

private class RecordingRasterizer : ContentRasterizer {
    val requestedPaths = mutableListOf<String>()

    override suspend fun render(
        assetPath: String,
        widthPixels: Int,
        firstSliceHeightPixels: Int,
        continuationSliceHeightPixels: Int,
        consumeSlice: (Bitmap) -> Unit,
    ): Int {
        requestedPaths += assetPath
        val bitmap = Bitmap.createBitmap(widthPixels, firstSliceHeightPixels / 2, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(205, 226, 255))
        return try {
            consumeSlice(bitmap)
            1
        } finally {
            bitmap.recycle()
        }
    }
}

private class BlockingRasterizer : ContentRasterizer {
    val started = CompletableDeferred<Unit>()
    val proceed = CompletableDeferred<Unit>()

    override suspend fun render(
        assetPath: String,
        widthPixels: Int,
        firstSliceHeightPixels: Int,
        continuationSliceHeightPixels: Int,
        consumeSlice: (Bitmap) -> Unit,
    ): Int {
        started.complete(Unit)
        proceed.await()
        val bitmap = Bitmap.createBitmap(widthPixels, firstSliceHeightPixels / 2, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        return try {
            consumeSlice(bitmap)
            1
        } finally {
            bitmap.recycle()
        }
    }
}
