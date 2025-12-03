package shell

import cats.effect.IO
import config.AppConfig
import flights.ApiKeyManager
import models._
import sttp.client3.SttpBackend

/** Immutable application state holding all loaded data */
final case class AppState(
    config: AppConfig,
    keyManager: ApiKeyManager,
    backend: SttpBackend[IO, Any],
    tournaments: List[Tournament],
    allBuckets: List[WeekendBucket],
    filteredBuckets: List[WeekendBucket],
    weekendQuotes: List[WeekendQuote],
    filteredQuotes: List[WeekendQuote]
) {

  /** Pure: get summary statistics */
  def summary: StateSummary = StateSummary(
    totalTournaments   = tournaments.size,
    totalBuckets       = allBuckets.size,
    filteredBuckets    = filteredBuckets.size,
    totalQuotes        = weekendQuotes.size,
    quotesWithPrice    = weekendQuotes.count(_.quote.isDefined),
    quotesWithoutPrice = weekendQuotes.count(_.quote.isEmpty),
    filteredQuotesCount = filteredQuotes.size
  )

  /** Pure: list unique airports in the data */
  def uniqueAirports: List[String] =
    weekendQuotes.map(_.bucket.key.airport.code).distinct.sorted

  /** Pure: list unique states/regions in the data */
  def uniqueStates: List[String] =
    tournaments.map(_.stateOrRegion).distinct.sorted

  /** Pure: get all tournament names */
  def tournamentNames: List[String] =
    tournaments.map(_.name).distinct.sorted
}

/** Summary statistics for display */
final case class StateSummary(
    totalTournaments: Int,
    totalBuckets: Int,
    filteredBuckets: Int,
    totalQuotes: Int,
    quotesWithPrice: Int,
    quotesWithoutPrice: Int,
    filteredQuotesCount: Int
)

object AppState {

  /** Create an empty initial state */
  def empty(
      config: AppConfig,
      keyManager: ApiKeyManager,
      backend: SttpBackend[IO, Any]
  ): AppState = AppState(
    config          = config,
    keyManager      = keyManager,
    backend         = backend,
    tournaments     = Nil,
    allBuckets      = Nil,
    filteredBuckets = Nil,
    weekendQuotes   = Nil,
    filteredQuotes  = Nil
  )
}
