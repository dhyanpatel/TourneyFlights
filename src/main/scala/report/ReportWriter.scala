package report

import cats.effect.IO
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import models.WeekendQuote

object ReportWriter {

  private val fileTimestampFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

  /** IO: write a markdown report to a file, returns the path written */
  def writeMarkdownReport(
      prefix: String,
      title: String,
      list: List[WeekendQuote]
  ): IO[Path] =
    IO {
      val generatedAt    = LocalDateTime.now()
      val generatedAtStr = generatedAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
      val timestamp      = generatedAt.format(fileTimestampFormat)
      val fileName       = s"${prefix}_${timestamp}.md"

      val content = ReportFormatter.formatMarkdown(title, generatedAtStr, list)
      val path    = Paths.get(fileName)
      Files.write(path, content.getBytes(StandardCharsets.UTF_8))
      path
    }

  /** IO: write a markdown report with custom filename */
  def writeMarkdownReportWithName(
      fileName: String,
      title: String,
      list: List[WeekendQuote]
  ): IO[Path] =
    IO {
      val generatedAt    = LocalDateTime.now()
      val generatedAtStr = generatedAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

      val content = ReportFormatter.formatMarkdown(title, generatedAtStr, list)
      val path    = Paths.get(fileName)
      Files.write(path, content.getBytes(StandardCharsets.UTF_8))
      path
    }
}
