package services

import config.{AppConfig, DepartureWindow}
import java.time.{LocalDateTime, LocalTime}
import java.time.format.DateTimeFormatter
import models._

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

  /** Pure: check if a quote meets price criteria */
  def meetsPriceCriteria(
      quote: FlightQuote,
      isFriendAirport: Boolean,
      maxPriceBase: Int,
      maxPriceFriend: Int
  ): Boolean =
    quote.priceUsd <= maxPriceBase ||
      (isFriendAirport && quote.priceUsd <= maxPriceFriend)

  /** Pure: filter weekend quotes by price and outbound departure window */
  def filterQuotes(quotes: List[WeekendQuote], config: AppConfig): List[WeekendQuote] =
    quotes.collect {
      case wq @ WeekendQuote(_, Some(q), isFriend)
          if meetsPriceCriteria(q, isFriend, config.maxPriceBase, config.maxPriceFriend) &&
            withinWindow(q.outboundDepartureTime, config.outboundWindow) =>
        wq
    }

  /** Pure: filter by maximum price */
  def filterByMaxPrice(quotes: List[WeekendQuote], maxPrice: Int): List[WeekendQuote] =
    quotes.filter {
      case WeekendQuote(_, Some(q), _) => q.priceUsd <= maxPrice
      case _                           => false
    }

  /** Pure: filter by airport code */
  def filterByAirport(quotes: List[WeekendQuote], airportCode: String): List[WeekendQuote] =
    quotes.filter(_.bucket.key.airport.code.equalsIgnoreCase(airportCode))

  /** Pure: filter by friend airports only */
  def filterFriendAirportsOnly(quotes: List[WeekendQuote]): List[WeekendQuote] =
    quotes.filter(_.isFriendAirport)

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

  /** Pure: sort quotes by price ascending */
  def sortByPrice(quotes: List[WeekendQuote]): List[WeekendQuote] =
    quotes.sortBy {
      case WeekendQuote(_, Some(q), _) => q.priceUsd
      case _                           => BigDecimal(Int.MaxValue)
    }

  /** Pure: sort quotes by date ascending */
  def sortByDate(quotes: List[WeekendQuote]): List[WeekendQuote] =
    quotes.sortBy(_.bucket.key.weekendStart)
}
