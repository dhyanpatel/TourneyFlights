package services

import java.time.{LocalDateTime, LocalTime}
import java.time.format.DateTimeFormatter
import models._

final case class DepartureWindow(
    earliest: Option[LocalTime],
    latest: Option[LocalTime]
)

object FlightFilterService {

  private val serpTimeFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

  /** Pure: parse a SERP API datetime string to LocalTime */
  def parseLocalTime(s: String): Option[LocalTime] =
    if (s == null || s.isEmpty) None
    else
      try Some(LocalDateTime.parse(s, serpTimeFormat).toLocalTime)
      catch { case _: Throwable => None }

  /** Pure: check if a time string falls within a departure window */
  def withinWindow(timeStr: String, window: DepartureWindow): Boolean = {
    if (window.earliest.isEmpty && window.latest.isEmpty) true
    else
      parseLocalTime(timeStr) match {
        case None => true
        case Some(t) =>
          window.earliest.forall(!t.isBefore(_)) &&
            window.latest.forall(!t.isAfter(_))
      }
  }

  /** Pure: filter by maximum price (uses cheapest quote) */
  def filterByMaxPrice(quotes: List[WeekendQuote], maxPrice: Int): List[WeekendQuote] =
    quotes.filter(_.cheapestQuote.exists(_.priceUsd <= maxPrice))

  /** Pure: filter by airport code */
  def filterByAirport(quotes: List[WeekendQuote], airportCode: String): List[WeekendQuote] =
    quotes.filter(_.bucket.key.airport.code.equalsIgnoreCase(airportCode))

  /** Pure: filter by state/region */
  def filterByState(quotes: List[WeekendQuote], state: String): List[WeekendQuote] =
    quotes.filter { wq =>
      wq.bucket.tournaments.exists(_.stateOrRegion.equalsIgnoreCase(state))
    }

  /** Pure: filter by tournament name substring */
  def filterByTournamentName(quotes: List[WeekendQuote], nameSubstring: String): List[WeekendQuote] = {
    val lower = nameSubstring.toLowerCase
    quotes.filter { wq =>
      wq.bucket.tournaments.exists(_.name.toLowerCase.contains(lower))
    }
  }

  /** Pure: sort quotes by price ascending (uses cheapest quote) */
  def sortByPrice(quotes: List[WeekendQuote]): List[WeekendQuote] =
    quotes.sortBy(_.cheapestQuote.map(_.priceUsd).getOrElse(BigDecimal(Int.MaxValue)))

  /** Pure: sort quotes by date ascending */
  def sortByDate(quotes: List[WeekendQuote]): List[WeekendQuote] =
    quotes.sortBy(_.bucket.key.weekendStart)
}
