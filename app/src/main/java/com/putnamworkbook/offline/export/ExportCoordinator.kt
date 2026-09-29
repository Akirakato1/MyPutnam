package com.putnamworkbook.offline.export

import com.putnamworkbook.offline.domain.model.ProblemKey
import kotlinx.coroutines.CancellationException
import java.io.OutputStream

class ExportCoordinator(
    private val exporter: WorkbookPdfExporter,
) {
    suspend fun export(
        key: ProblemKey,
        options: WorkbookExportOptions,
        destination: OutputStream,
    ): ExportResult = try {
        destination.use { exporter.export(key, options, it) }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        ExportResult.Failure(error.message?.take(240) ?: "PDF destination could not be closed")
    }
}
