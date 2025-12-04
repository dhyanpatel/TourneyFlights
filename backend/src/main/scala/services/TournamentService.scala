package services

import java.time.{DayOfWeek, LocalDate}
import java.time.temporal.TemporalAdjusters
import models._

object TournamentService {

  /** Pure: compute the Friday of the week containing this date */
  def weekendKey(d: LocalDate): LocalDate =
    d.`with`(TemporalAdjusters.previousOrSame(DayOfWeek.FRIDAY))

  /** Pure: attach metro airport to each tournament, drop those without mapping */
  def attachAirports(tournaments: List[Tournament]): List[(Tournament, MetroAirport)] =
    tournaments.flatMap { t =>
      MetroMapping.airportFor(t.city, t.stateOrRegion).map(a => (t, a))
    }

  /** Pure: group tournaments into weekend buckets by airport and weekend start */
  def toWeekendBuckets(tournaments: List[Tournament]): List[WeekendBucket] = {
    val withMetros = attachAirports(tournaments)

    withMetros
      .groupBy { case (t, a) => WeekendKey(a, weekendKey(t.startDate)) }
      .toList
      .map { case (key, list) => WeekendBucket(key, list.map(_._1)) }
  }

  /** Pure: filter buckets to those within the next N months from today */
  def filterByDateRange(
      buckets: List[WeekendBucket],
      months: Long,
      today: LocalDate = LocalDate.now()
  ): List[WeekendBucket] = {
    val cutoff = today.plusMonths(months)
    buckets.filter { b =>
      val d = b.key.weekendStart
      !d.isBefore(today) && !d.isAfter(cutoff)
    }
  }

  /** Pure: sort buckets by weekend start then airport code */
  def sortBuckets(buckets: List[WeekendBucket]): List[WeekendBucket] =
    buckets.sortBy(b => (b.key.weekendStart, b.key.airport.code))
}
