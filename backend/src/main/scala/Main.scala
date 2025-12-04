import cats.effect.IO
import cats.effect.IOApp
import cats.syntax.traverse._
import config.AppConfig
import flights.{ApiKeyManager, FlightsClient}
import models._
import scraper.TournamentScraper
import services.{FlightFilterService, TournamentService}
import shell.{AppState, InteractiveShell}
import sttp.client3._
import sttp.client3.httpclient.cats.HttpClientCatsBackend

object Main extends IOApp.Simple {

  private val omnipongUrl = uri"https://omnipong.com/t-tourney.asp?e=0"

  // ─────────────────────────────────────────────────────────────────────────────
  // Pipeline Steps (pure transformations composed in the controller)
  // ─────────────────────────────────────────────────────────────────────────────

  /** Step 1: Scrape tournaments from HTML */
  private def scrapeTournaments(html: String): List[Tournament] =
    TournamentScraper.parseTournaments(html)

  /** Step 2: Group tournaments into weekend buckets */
  private def groupIntoWeekendBuckets(tournaments: List[Tournament]): List[WeekendBucket] =
    TournamentService.toWeekendBuckets(tournaments)

  /** Step 3: Filter buckets by date range */
  private def filterByDateRange(buckets: List[WeekendBucket], months: Long): List[WeekendBucket] =
    TournamentService.filterByDateRange(buckets, months)

  /** Step 4: Sort buckets for consistent ordering */
  private def sortBuckets(buckets: List[WeekendBucket]): List[WeekendBucket] =
    TournamentService.sortBuckets(buckets)

  /** Step 5: Fetch flight quotes for buckets (IO) */
  private def fetchFlightQuotes(
      client: FlightsClient,
      buckets: List[WeekendBucket],
      config: AppConfig
  ): IO[List[WeekendQuote]] = {
    val toCall = buckets.take(config.maxApiCallsPerRun)

    toCall.traverse { bucket =>
      val depart = bucket.key.weekendStart
      val ret    = depart.plusDays(2)

      IO.println(s"Querying: ${config.originAirport} -> ${bucket.key.airport.code} ($depart to $ret)") *>
        client
          .roundTripOptions(config.originAirport, bucket.key.airport.code, depart, ret)
          .map { quotes =>
            val isFriend = config.friendAirports.contains(bucket.key.airport.code)
            val cheapest = quotes.sortBy(_.priceUsd).headOption
            WeekendQuote(bucket, cheapest, isFriend)
          }
    }
  }

  /** Step 6: Apply filters to quotes */
  private def applyFilters(quotes: List[WeekendQuote], config: AppConfig): List[WeekendQuote] =
    FlightFilterService.filterQuotes(quotes, config)

  // ─────────────────────────────────────────────────────────────────────────────
  // Controller: Orchestrates the pipeline
  // ─────────────────────────────────────────────────────────────────────────────

  /** Load all data and build application state */
  private def loadAppState(
      backend: SttpBackend[IO, Any],
      config: AppConfig,
      keyManager: ApiKeyManager
  ): IO[AppState] = {
    val flightsClient = new FlightsClient(backend, keyManager, config.cacheDir)

    for {
      // Reset key manager to first key for fresh load
      _        <- keyManager.reset

      // Fetch HTML from source
      _        <- IO.println("Fetching tournament data...")
      response <- basicRequest.get(omnipongUrl).send(backend)
      body     <- IO.fromEither(response.body.left.map(new Exception(_)))

      // Pure transformation pipeline
      tournaments     = scrapeTournaments(body)
      _              <- IO.println(s"Scraped ${tournaments.size} tournaments")

      allBuckets      = groupIntoWeekendBuckets(tournaments)
      _              <- IO.println(s"Grouped into ${allBuckets.size} weekend buckets")

      filteredBuckets = filterByDateRange(allBuckets, config.filterMonths)
      _              <- IO.println(s"Filtered to ${filteredBuckets.size} buckets (next ${config.filterMonths} months)")

      sortedBuckets   = sortBuckets(filteredBuckets)

      // IO: Fetch flight quotes
      _              <- IO.println(s"Fetching flight quotes (max ${config.maxApiCallsPerRun}, ${keyManager.keyCount} API keys available)...")
      weekendQuotes  <- fetchFlightQuotes(flightsClient, sortedBuckets, config)
      _              <- IO.println(s"Fetched ${weekendQuotes.size} quotes")

      // Pure: Apply filters
      filteredQuotes  = applyFilters(weekendQuotes, config)
      _              <- IO.println(s"${filteredQuotes.size} quotes match your filters")

    } yield AppState(
      config          = config,
      keyManager      = keyManager,
      backend         = backend,
      tournaments     = tournaments,
      allBuckets      = allBuckets,
      filteredBuckets = filteredBuckets,
      weekendQuotes   = weekendQuotes,
      filteredQuotes  = filteredQuotes
    )
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Entry Point
  // ─────────────────────────────────────────────────────────────────────────────

  def run: IO[Unit] =
    HttpClientCatsBackend.resource[IO]().use { backend =>
      val config = AppConfig.load()

      for {
        keyManager   <- ApiKeyManager.create(config.apiKeys)
        _            <- IO.println(s"Loaded ${keyManager.keyCount} API key(s)")
        reloadAction  = loadAppState(backend, config, keyManager)
        initialState <- reloadAction
        _            <- IO.println("")
        _            <- InteractiveShell.run(initialState, reloadAction)
      } yield ()
    }
}
