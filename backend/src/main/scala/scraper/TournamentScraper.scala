package scraper

import java.time.format.DateTimeFormatter
import java.time.LocalDate
import models.Tournament
import org.jsoup.nodes.Element
import org.jsoup.Jsoup
import scala.jdk.CollectionConverters._

object TournamentScraper {
  private val dateFmt = DateTimeFormatter.ofPattern("MM/dd/yy")

  private def parseDateRange(text: String): (LocalDate, LocalDate) = {
    val parts = text.split("-").map(_.trim)
    if (parts.length == 1) {
      val d = LocalDate.parse(parts(0), dateFmt)
      (d, d)
    } else {
      val start = LocalDate.parse(parts(0), dateFmt)
      val end = LocalDate.parse(parts(1), dateFmt)
      (start, end)
    }
  }

  def parseTournaments(html: String): List[Tournament] = {
    val doc = Jsoup.parse(html)
    val container = doc.select("td.omnipong").first()
    if (container == null) return Nil

    val tables = container.select("table.omnipong").asScala.toList

    tables.flatMap { table =>
      val heading = findHeadingForTable(table).map(_.text().trim).getOrElse("")
      val rows = table.select("tr").asScala.toList.drop(1)

      rows.flatMap { tr =>
        val tds = tr.select("td").asScala.toList
        if (tds.length >= 5) {
          val name = tds(2).text().trim
          val locationText = tds(3).text().trim
          val dateText = tds(4).text().trim

          if (name.nonEmpty && locationText.nonEmpty && dateText.nonEmpty) {
            val (city, stateOrRegion) =
              if (heading == "USATT Events") {
                val parts = locationText.split(",").map(_.trim)
                if (parts.length >= 2) (parts(0), parts(1)) else (locationText, "")
              } else {
                (locationText, heading)
              }

            val (start, end) = parseDateRange(dateText)
            Some(Tournament(name, city, stateOrRegion, start, end, dateText))
          } else None
        } else None
      }
    }
  }

  private def findHeadingForTable(table: Element): Option[Element] = {
    var prev = table.previousElementSibling()
    while (prev != null && prev.tagName() != "h3")
      prev = prev.previousElementSibling()
    Option(prev)
  }
}
