package com.lecturecaption.app.export

import android.content.Context
import androidx.core.content.FileProvider
import com.lecturecaption.app.data.db.entity.Lecture
import com.lecturecaption.app.data.db.entity.TranscriptSegment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfDocument

object TranscriptExporter {

    private fun timestamp(ms: Long): String {
        val h = TimeUnit.MILLISECONDS.toHours(ms)
        val m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        val s = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
        return if (h > 0) "%02d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    fun toTxt(lecture: Lecture, segments: List<TranscriptSegment>): String = buildString {
        appendLine(lecture.title)
        appendLine("Recorded: ${SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()).format(Date(lecture.createdAt))}")
        appendLine()
        for (seg in segments) {
            appendLine("[${timestamp(seg.startTimeMs)}] ${seg.text}")
        }
    }

    fun toMarkdown(lecture: Lecture, segments: List<TranscriptSegment>): String = buildString {
        appendLine("# ${lecture.title}")
        appendLine()
        appendLine("*Recorded ${SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()).format(Date(lecture.createdAt))} — ${lecture.language} — ${lecture.transcriptionEngine}*")
        appendLine()
        for (seg in segments) {
            appendLine("**${timestamp(seg.startTimeMs)}**")
            appendLine()
            appendLine(seg.text)
            appendLine()
        }
    }

    /** Writes to app-private cache and returns a content:// Uri suitable for a Share/Send intent. */
    fun writeAndGetShareUri(context: Context, lecture: Lecture, content: String, extension: String): android.net.Uri {
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val safeName = lecture.title.replace(Regex("[^A-Za-z0-9_\\- ]"), "_")
        val file = File(exportDir, "$safeName.$extension")
        file.writeText(content)
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    /** Saves a named .txt copy of the transcript in the app's Documents folder; returns the file. */
    fun saveTxtFile(context: Context, lecture: Lecture, segments: List<TranscriptSegment>): File {
        val base = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
        val dir = File(base, "LectureCaption").apply { mkdirs() }
        val safeName = lecture.title.replace(Regex("[^A-Za-z0-9_\\- ]"), "_").ifBlank { "Lecture" }
        var file = File(dir, "$safeName.txt")
        var n = 1
        while (file.exists()) { file = File(dir, "$safeName ($n).txt"); n++ }
        file.writeText(toTxt(lecture, segments))
        return file
    }

    /** Builds a simple, paginated A4 PDF (title + timestamped segments) and returns the file. */
    private fun buildPdf(lecture: Lecture, segments: List<TranscriptSegment>): PdfDocument {
        val pageWidth = 595   // A4 at 72dpi
        val pageHeight = 842
        val margin = 48f
        val lineHeight = 18f

        val titlePaint = Paint().apply { textSize = 18f; isFakeBoldText = true }
        val metaPaint = Paint().apply { textSize = 11f; color = android.graphics.Color.DKGRAY }
        val timePaint = Paint().apply { textSize = 11f; isFakeBoldText = true }
        val textPaint = Paint().apply { textSize = 13f }

        val doc = PdfDocument()
        var page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, doc.pages.size + 1).create())
        var canvas: Canvas = page.canvas
        var y = margin

        fun newPage() {
            doc.finishPage(page)
            page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, doc.pages.size + 1).create())
            canvas = page.canvas
            y = margin
        }

        fun ensureSpace(needed: Float) {
            if (y + needed > pageHeight - margin) newPage()
        }

        canvas.drawText(lecture.title, margin, y, titlePaint); y += lineHeight * 1.4f
        canvas.drawText(
            "Recorded: ${SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()).format(Date(lecture.createdAt))}  •  " +
                "${lecture.language}  •  ${lecture.transcriptionEngine}",
            margin, y, metaPaint
        )
        y += lineHeight * 1.8f

        val maxTextWidth = pageWidth - margin * 2
        for (seg in segments) {
            ensureSpace(lineHeight * 2f)
            canvas.drawText("[${timestamp(seg.startTimeMs)}]", margin, y, timePaint)
            y += lineHeight

            var remaining = seg.text
            while (remaining.isNotEmpty()) {
                var cut = remaining.length
                while (cut > 0 && textPaint.measureText(remaining, 0, cut) > maxTextWidth) cut--
                if (cut < remaining.length) {
                    val lastSpace = remaining.lastIndexOf(' ', cut)
                    if (lastSpace > 0) cut = lastSpace
                }
                if (cut <= 0) cut = 1
                ensureSpace(lineHeight)
                canvas.drawText(remaining.substring(0, cut).trim(), margin, y, textPaint)
                y += lineHeight
                remaining = remaining.substring(cut).trim()
            }
            y += lineHeight * 0.4f
        }
        doc.finishPage(page)
        return doc
    }

    /** Writes a PDF to app cache and returns a content:// Uri suitable for a Share/Send intent. */
    fun writePdfAndGetShareUri(context: Context, lecture: Lecture, segments: List<TranscriptSegment>): android.net.Uri {
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val safeName = lecture.title.replace(Regex("[^A-Za-z0-9_\\- ]"), "_").ifBlank { "Lecture" }
        val file = File(exportDir, "$safeName.pdf")
        val doc = buildPdf(lecture, segments)
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    /** Saves a named PDF copy of the transcript in the app's Documents folder; returns the file. */
    fun savePdfFile(context: Context, lecture: Lecture, segments: List<TranscriptSegment>): File {
        val base = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
        val dir = File(base, "LectureCaption").apply { mkdirs() }
        val safeName = lecture.title.replace(Regex("[^A-Za-z0-9_\\- ]"), "_").ifBlank { "Lecture" }
        var file = File(dir, "$safeName.pdf")
        var n = 1
        while (file.exists()) { file = File(dir, "$safeName ($n).pdf"); n++ }
        val doc = buildPdf(lecture, segments)
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        return file
    }
}
