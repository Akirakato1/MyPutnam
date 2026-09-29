package com.putnamworkbook.offline.notebook.storage

import com.putnamworkbook.offline.domain.model.BrushSpec
import com.putnamworkbook.offline.notebook.model.StrokeRecord
import com.putnamworkbook.offline.notebook.model.StrokeSample
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

object StrokeCodec {
    fun encode(stroke: StrokeRecord): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(MAGIC)
            output.writeInt(VERSION)
            output.writeUTF(stroke.id)
            output.writeLong(stroke.brush.colorArgb)
            output.writeFloat(stroke.brush.width)
            output.writeInt(stroke.samples.size)
            stroke.samples.forEach { sample ->
                output.writeFloat(sample.x)
                output.writeFloat(sample.y)
                output.writeLong(sample.elapsedMillis)
                output.writeBoolean(sample.pressure != null)
                sample.pressure?.let(output::writeFloat)
            }
        }
        bytes.toByteArray()
    }

    fun decode(bytes: ByteArray): StrokeRecord = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        if (input.readInt() != MAGIC) throw IOException("Invalid stroke file signature")
        val version = input.readInt()
        if (version != VERSION) throw IOException("Unsupported stroke version: $version")
        val id = input.readUTF()
        val brush = BrushSpec(input.readLong(), input.readFloat())
        val sampleCount = input.readInt()
        if (sampleCount !in 1..MAX_SAMPLES) throw IOException("Invalid stroke sample count: $sampleCount")
        val samples = List(sampleCount) {
            StrokeSample(
                x = input.readFloat(),
                y = input.readFloat(),
                elapsedMillis = input.readLong(),
                pressure = if (input.readBoolean()) input.readFloat() else null,
            )
        }
        if (input.available() != 0) throw IOException("Stroke file contains trailing data")
        StrokeRecord(id, brush, samples)
    }

    private const val MAGIC = 0x50575354
    private const val VERSION = 1
    private const val MAX_SAMPLES = 1_000_000
}
