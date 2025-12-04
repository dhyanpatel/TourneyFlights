package api

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.syntax.traverse._
import flights.{AccountClient, ApiKeyManager, CacheInfo, FlightsClient}
import fs2.Stream
import io.circe._
import io.circe.generic.semiauto._
import io.circe.syntax._
import models._
import org.http4s._
import org.http4s.circe._
import org.http4s.dsl.io._
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.CIString
import scraper.TournamentScraper
import services.{FlightFilterService, TournamentService}
import sttp.client3.{SttpBackend => _, Response => _, Request => _, _}
import sttp.client3.SttpBackend
import java.nio.file.{Path, Paths}
import java.time.{Instant, LocalDate, LocalTime}
import java.util.UUID

object ApiRoutes {

  // ─────────────────────────────────────────────────────────────────────────────
  // Session Configuration (user-provided settings)
  // ─────────────────────────────────────────────────────────────────────────────

  case class SessionConfig(
      originAirport: String,
      friendAirports: Set[String],
      filterMonths: Long,
      tripDurationDays: Int
  )

  object SessionConfig {
    val default: SessionConfig = SessionConfig(
      originAirport = "ORD",
      friendAirports = Set.empty,
      filterMonths = 3,
      tripDurationDays = 2
    )
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // JSON Encoders/Decoders
  // ─────────────────────────────────────────────────────────────────────────────

  implicit val localDateEncoder: Encoder[LocalDate] = Encoder.encodeString.contramap(_.toString)
  implicit val localDateDecoder: Decoder[LocalDate] = Decoder.decodeString.emap(s =>
    scala.util.Try(LocalDate.parse(s)).toEither.left.map(_.getMessage)
  )
  implicit val instantEncoder: Encoder[Instant] = Encoder.encodeString.contramap(_.toString)

  implicit val metroAirportEncoder: Encoder[MetroAirport] = Encoder.encodeString.contramap(_.code)

  implicit val tournamentEncoder: Encoder[Tournament] = deriveEncoder[Tournament]
  implicit val weekendKeyEncoder: Encoder[WeekendKey] = deriveEncoder[WeekendKey]
  implicit val weekendBucketEncoder: Encoder[WeekendBucket] = deriveEncoder[WeekendBucket]
  implicit val flightQuoteEncoder: Encoder[FlightQuote] = deriveEncoder[FlightQuote]
  implicit val quoteCacheInfoEncoder: Encoder[QuoteCacheInfo] = deriveEncoder[QuoteCacheInfo]
  implicit val weekendQuoteEncoder: Encoder[WeekendQuote] = deriveEncoder[WeekendQuote]
  implicit val sessionConfigEncoder: Encoder[SessionConfig] = deriveEncoder[SessionConfig]
  implicit val sessionConfigDecoder: Decoder[SessionConfig] = deriveDecoder[SessionConfig]

  case class ErrorResponse(error: String)
  implicit val errorResponseEncoder: Encoder[ErrorResponse] = deriveEncoder[ErrorResponse]

  case class HealthResponse(status: String, activeSessions: Int)
  implicit val healthResponseEncoder: Encoder[HealthResponse] = deriveEncoder[HealthResponse]

  // Request/Response types
  case class CreateSessionRequest(
      apiKeys: List[String],
      config: Option[SessionConfig]
  )
  implicit val createSessionRequestDecoder: Decoder[CreateSessionRequest] = deriveDecoder[CreateSessionRequest]

  case class SessionResponse(
      sessionId: String,
      config: SessionConfig,
      totalTournaments: Int,
      totalBuckets: Int,
      message: String
  )
  implicit val sessionResponseEncoder: Encoder[SessionResponse] = deriveEncoder[SessionResponse]

  case class UpdateConfigRequest(
      originAirport: Option[String],
      friendAirports: Option[Set[String]],
      filterMonths: Option[Long],
      tripDurationDays: Option[Int]
  )
  implicit val updateConfigRequestDecoder: Decoder[UpdateConfigRequest] = deriveDecoder[UpdateConfigRequest]

  case class SearchFlightsRequest(
      originAirport: Option[String],
      destinationAirport: Option[String],
      departureDate: Option[LocalDate],
      returnDate: Option[LocalDate],
      maxResults: Option[Int],
      skipCache: Option[Boolean]
  )
  implicit val searchFlightsRequestDecoder: Decoder[SearchFlightsRequest] = deriveDecoder[SearchFlightsRequest]

  case class FlightSearchResult(
      origin: String,
      destination: String,
      departureDate: LocalDate,
      returnDate: LocalDate,
      quotes: List[FlightQuote],
      cacheInfo: QuoteCacheInfo
  )
  implicit val flightSearchResultEncoder: Encoder[FlightSearchResult] = deriveEncoder[FlightSearchResult]

  case class SearchFlightsResponse(
      results: List[FlightSearchResult],
      totalQuotes: Int
  )
  implicit val searchFlightsResponseEncoder: Encoder[SearchFlightsResponse] = deriveEncoder[SearchFlightsResponse]

  case class QuotesResponse(count: Int, quotes: List[WeekendQuote])
  implicit val quotesResponseEncoder: Encoder[QuotesResponse] = deriveEncoder[QuotesResponse]

  case class SessionInfoResponse(
      sessionId: String,
      config: SessionConfig,
      createdAt: Instant,
      expiresAt: Instant,
      totalTournaments: Int,
      totalBuckets: Int,
      quotesLoaded: Int,
      apiKeyCount: Int
  )
  implicit val sessionInfoResponseEncoder: Encoder[SessionInfoResponse] = deriveEncoder[SessionInfoResponse]

  case class AirportsResponse(airports: List[String])
  implicit val airportsResponseEncoder: Encoder[AirportsResponse] = deriveEncoder[AirportsResponse]

  case class StatesResponse(states: List[String])
  implicit val statesResponseEncoder: Encoder[StatesResponse] = deriveEncoder[StatesResponse]

  case class TournamentsResponse(tournaments: List[String])
  implicit val tournamentsResponseEncoder: Encoder[TournamentsResponse] = deriveEncoder[TournamentsResponse]

  case class ApiKeyUsage(
      maskedKey: String,
      accountEmail: Option[String],
      planName: Option[String],
      searchesPerMonth: Option[Int],
      thisMonthUsage: Option[Int],
      planSearchesLeft: Option[Int],
      extraCredits: Option[Int],
      totalSearchesLeft: Option[Int],
      error: Option[String]
  )
  implicit val apiKeyUsageEncoder: Encoder[ApiKeyUsage] = deriveEncoder[ApiKeyUsage]

  case class ApiKeyUsageResponse(keys: List[ApiKeyUsage])
  implicit val apiKeyUsageResponseEncoder: Encoder[ApiKeyUsageResponse] = deriveEncoder[ApiKeyUsageResponse]

  // SSE Progress Events
  case class SearchProgress(
      current: Int,
      total: Int,
      destination: String,
      departureDate: LocalDate,
      fromCache: Boolean,
      priceUsd: Option[BigDecimal]
  )
  implicit val searchProgressEncoder: Encoder[SearchProgress] = deriveEncoder[SearchProgress]

  case class SearchComplete(
      results: List[FlightSearchResult],
      totalQuotes: Int
  )
  implicit val searchCompleteEncoder: Encoder[SearchComplete] = deriveEncoder[SearchComplete]

  case class SearchError(error: String)
  implicit val searchErrorEncoder: Encoder[SearchError] = deriveEncoder[SearchError]

  // ─────────────────────────────────────────────────────────────────────────────
  // Query Parameter Matchers
  // ─────────────────────────────────────────────────────────────────────────────

  object AirportParam extends OptionalQueryParamDecoderMatcher[String]("airport")
  object StateParam extends OptionalQueryParamDecoderMatcher[String]("state")
  object MaxPriceParam extends OptionalQueryParamDecoderMatcher[Int]("maxPrice")
  object SearchParam extends OptionalQueryParamDecoderMatcher[String]("search")
  object LimitParam extends OptionalQueryParamDecoderMatcher[Int]("limit")
  object SkipCacheParam extends OptionalQueryParamDecoderMatcher[Boolean]("skipCache")

  private val omnipongUrl = uri"https://omnipong.com/t-tourney.asp?e=0"

  // Session TTL: 1 hour
  private val SESSION_TTL_SECONDS = 3600L
  private val CACHE_TTL_SECONDS = 24L * 60 * 60
  private val cacheDir: Path = Paths.get("flight_cache")

  // ─────────────────────────────────────────────────────────────────────────────
  // Session Data
  // ─────────────────────────────────────────────────────────────────────────────

  case class SessionData(
      sessionId: String,
      apiKeys: List[String],
      config: SessionConfig,
      tournaments: List[Tournament],
      allBuckets: List[WeekendBucket],
      weekendQuotes: List[WeekendQuote],
      createdAt: Instant
  ) {
    def maskKey(key: String): String =
      if (key.length <= 8) "****" else s"${key.take(4)}...${key.takeRight(4)}"

    def isExpired: Boolean =
      Instant.now().getEpochSecond - createdAt.getEpochSecond > SESSION_TTL_SECONDS

    def expiresAt: Instant =
      Instant.ofEpochSecond(createdAt.getEpochSecond + SESSION_TTL_SECONDS)

    def filteredBuckets: List[WeekendBucket] =
      TournamentService.filterByDateRange(allBuckets, config.filterMonths)

    def withConfig(newConfig: SessionConfig): SessionData =
      copy(config = newConfig)
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Helper: Convert CacheInfo to QuoteCacheInfo
  // ─────────────────────────────────────────────────────────────────────────────

  private def toQuoteCacheInfo(ci: CacheInfo): QuoteCacheInfo =
    QuoteCacheInfo(ci.fromCache, ci.cacheAgeSeconds, ci.cachedAt)

  // ─────────────────────────────────────────────────────────────────────────────
  // Data Loading Pipeline
  // ─────────────────────────────────────────────────────────────────────────────

  private def loadTournaments(
      backend: SttpBackend[IO, Any]
  ): IO[(List[Tournament], List[WeekendBucket])] = {
    for {
      response    <- basicRequest.get(omnipongUrl).send(backend)
      body        <- IO.fromEither(response.body.left.map(new Exception(_)))
      tournaments  = TournamentScraper.parseTournaments(body)
      allBuckets   = TournamentService.toWeekendBuckets(tournaments)
    } yield (tournaments, allBuckets)
  }

  private def fetchQuotesForBuckets(
      backend: SttpBackend[IO, Any],
      apiKeys: List[String],
      buckets: List[WeekendBucket],
      config: SessionConfig,
      skipCache: Boolean = false,
      maxResults: Option[Int] = None
  ): IO[List[WeekendQuote]] = {
    for {
      keyManager    <- ApiKeyManager.create(apiKeys)
      flightsClient  = new FlightsClient(backend, keyManager, cacheDir, CACHE_TTL_SECONDS)
      limitedBuckets = maxResults.fold(buckets)(n => buckets.take(n))

      weekendQuotes <- limitedBuckets.traverse { bucket =>
        val depart = bucket.key.weekendStart
        val ret    = depart.plusDays(config.tripDurationDays)
        flightsClient
          .roundTripOptions(config.originAirport, bucket.key.airport.code, depart, ret, skipCache)
          .map { case (quotes, cacheInfo) =>
            WeekendQuote(bucket, quotes, toQuoteCacheInfo(cacheInfo))
          }
      }
    } yield weekendQuotes
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Routes
  // ─────────────────────────────────────────────────────────────────────────────

  private val SessionHeader = "X-Session-Id"

  def routes(backend: SttpBackend[IO, Any], sessions: Ref[IO, Map[String, SessionData]]): HttpRoutes[IO] = {
    import org.http4s.circe.CirceEntityDecoder._

    def getSessionId(req: Request[IO]): Option[String] =
      req.headers.get(CIString(SessionHeader)).map(_.head.value)

    def getSession(req: Request[IO]): IO[Option[SessionData]] =
      getSessionId(req) match {
        case None => IO.pure(None)
        case Some(id) => sessions.get.map(_.get(id).filterNot(_.isExpired))
      }

    def withSession(req: Request[IO])(f: SessionData => IO[org.http4s.Response[IO]]): IO[org.http4s.Response[IO]] =
      getSession(req).flatMap {
        case None =>
          IO.pure(org.http4s.Response[IO](Status.Unauthorized).withEntity(ErrorResponse("Missing or invalid session. Call POST /api/session first with your API keys.").asJson))
        case Some(data) =>
          f(data)
      }

    def withSessionUpdate(req: Request[IO])(f: SessionData => IO[(SessionData, org.http4s.Response[IO])]): IO[org.http4s.Response[IO]] =
      getSessionId(req) match {
        case None =>
          IO.pure(org.http4s.Response[IO](Status.Unauthorized).withEntity(ErrorResponse("Missing or invalid session.").asJson))
        case Some(sessionId) =>
          sessions.get.flatMap(_.get(sessionId).filterNot(_.isExpired) match {
            case None =>
              IO.pure(org.http4s.Response[IO](Status.Unauthorized).withEntity(ErrorResponse("Missing or invalid session.").asJson))
            case Some(data) =>
              f(data).flatMap { case (updatedData, response) =>
                sessions.update(_ + (sessionId -> updatedData)) *> IO.pure(response)
              }
          })
      }

    HttpRoutes.of[IO] {

      // ─────────────────────────────────────────────────────────────────────────
      // Health & Session Management
      // ─────────────────────────────────────────────────────────────────────────

      // GET /api/health - Health check endpoint (no auth required)
      case GET -> Root / "api" / "health" =>
        sessions.get.flatMap { sessionsMap =>
          val activeCount = sessionsMap.count { case (_, data) => !data.isExpired }
          Ok(HealthResponse("ok", activeCount).asJson)
        }

      // POST /api/session - Create session with user-provided API keys and optional config
      case req @ POST -> Root / "api" / "session" =>
        req.as[CreateSessionRequest].flatMap { body =>
          if (body.apiKeys.isEmpty) {
            BadRequest(ErrorResponse("apiKeys list cannot be empty").asJson)
          } else {
            val sessionId = UUID.randomUUID().toString
            val userConfig = body.config.getOrElse(SessionConfig.default)

            loadTournaments(backend).attempt.flatMap {
              case Right((tournaments, allBuckets)) =>
                val sessionData = SessionData(
                  sessionId = sessionId,
                  apiKeys = body.apiKeys,
                  config = userConfig,
                  tournaments = tournaments,
                  allBuckets = allBuckets,
                  weekendQuotes = List.empty,
                  createdAt = Instant.now()
                )
                sessions.update(_ + (sessionId -> sessionData)) *>
                  Ok(SessionResponse(
                    sessionId = sessionId,
                    config = userConfig,
                    totalTournaments = tournaments.size,
                    totalBuckets = allBuckets.size,
                    message = "Session created. Use GET /api/session to view details, PATCH /api/session/config to update settings, POST /api/flights/search to fetch flight quotes."
                  ).asJson)
              case Left(err) =>
                InternalServerError(ErrorResponse(s"Failed to load tournament data: ${err.getMessage}").asJson)
            }
          }
        }

      // GET /api/session - Get current session info
      case req @ GET -> Root / "api" / "session" =>
        withSession(req) { data =>
          Ok(SessionInfoResponse(
            sessionId = data.sessionId,
            config = data.config,
            createdAt = data.createdAt,
            expiresAt = data.expiresAt,
            totalTournaments = data.tournaments.size,
            totalBuckets = data.allBuckets.size,
            quotesLoaded = data.weekendQuotes.size,
            apiKeyCount = data.apiKeys.size
          ).asJson)
        }

      // DELETE /api/session - End session
      case req @ DELETE -> Root / "api" / "session" =>
        getSessionId(req) match {
          case None =>
            BadRequest(ErrorResponse(s"Missing $SessionHeader header").asJson)
          case Some(sessionId) =>
            sessions.update(_ - sessionId) *>
              Ok(Json.obj("message" -> Json.fromString("Session ended")))
        }

      // PATCH /api/session/config - Update session configuration
      case req @ PATCH -> Root / "api" / "session" / "config" =>
        req.as[UpdateConfigRequest].flatMap { update =>
          withSessionUpdate(req) { data =>
            val newConfig = data.config.copy(
              originAirport = update.originAirport.getOrElse(data.config.originAirport),
              friendAirports = update.friendAirports.getOrElse(data.config.friendAirports),
              filterMonths = update.filterMonths.getOrElse(data.config.filterMonths),
              tripDurationDays = update.tripDurationDays.getOrElse(data.config.tripDurationDays)
            )
            val updatedData = data.withConfig(newConfig)
            Ok(Json.obj(
              "message" -> Json.fromString("Configuration updated"),
              "config" -> newConfig.asJson
            )).map(resp => (updatedData, resp))
          }
        }

      // ─────────────────────────────────────────────────────────────────────────
      // Flight Search
      // ─────────────────────────────────────────────────────────────────────────

      // POST /api/flights/search - Search for flights with custom parameters
      case req @ POST -> Root / "api" / "flights" / "search" =>
        req.as[SearchFlightsRequest].flatMap { searchReq =>
          withSessionUpdate(req) { data =>
            val origin = searchReq.originAirport.getOrElse(data.config.originAirport)
            val skipCache = searchReq.skipCache.getOrElse(false)
            val maxResults = searchReq.maxResults

            // Determine which buckets to search
            val bucketsToSearch = (searchReq.destinationAirport, searchReq.departureDate) match {
              case (Some(dest), Some(depDate)) =>
                // Specific destination and date
                val retDate = searchReq.returnDate.getOrElse(depDate.plusDays(data.config.tripDurationDays))
                List((dest, depDate, retDate))
              case (Some(dest), None) =>
                // Specific destination, use filtered buckets for dates
                data.filteredBuckets
                  .filter(_.key.airport.code.equalsIgnoreCase(dest))
                  .map(b => (b.key.airport.code, b.key.weekendStart, b.key.weekendStart.plusDays(data.config.tripDurationDays)))
              case (None, Some(depDate)) =>
                // Specific date, all destinations
                data.filteredBuckets
                  .filter(_.key.weekendStart == depDate)
                  .map(b => (b.key.airport.code, depDate, searchReq.returnDate.getOrElse(depDate.plusDays(data.config.tripDurationDays))))
              case (None, None) =>
                // Use all filtered buckets
                data.filteredBuckets
                  .map(b => (b.key.airport.code, b.key.weekendStart, b.key.weekendStart.plusDays(data.config.tripDurationDays)))
            }

            val limitedBuckets = maxResults.fold(bucketsToSearch)(n => bucketsToSearch.take(n))

            for {
              keyManager    <- ApiKeyManager.create(data.apiKeys)
              flightsClient  = new FlightsClient(backend, keyManager, cacheDir, CACHE_TTL_SECONDS)

              results <- limitedBuckets.traverse { case (dest, depDate, retDate) =>
                flightsClient.roundTripOptions(origin, dest, depDate, retDate, skipCache).map {
                  case (quotes, cacheInfo) =>
                    FlightSearchResult(
                      origin = origin,
                      destination = dest,
                      departureDate = depDate,
                      returnDate = retDate,
                      quotes = quotes.sortBy(_.priceUsd),
                      cacheInfo = toQuoteCacheInfo(cacheInfo)
                    )
                }
              }

              // Update session with new quotes
              newQuotes = results.flatMap { result =>
                val bucket = data.allBuckets.find(b =>
                  b.key.airport.code == result.destination &&
                  b.key.weekendStart == result.departureDate
                )
                bucket.map { b =>
                  WeekendQuote(b, result.quotes, result.cacheInfo)
                }
              }

              // Merge new quotes with existing, preferring newer ones
              existingQuotesMap = data.weekendQuotes.map(q => (q.bucket.key.airport.code, q.bucket.key.weekendStart) -> q).toMap
              newQuotesMap = newQuotes.map(q => (q.bucket.key.airport.code, q.bucket.key.weekendStart) -> q).toMap
              mergedQuotes = (existingQuotesMap ++ newQuotesMap).values.toList

              updatedData = data.copy(weekendQuotes = mergedQuotes)
              response <- Ok(SearchFlightsResponse(
                results = results,
                totalQuotes = results.map(_.quotes.size).sum
              ).asJson)
            } yield (updatedData, response)
          }
        }

      // POST /api/flights/search/stream - Search for flights with SSE progress updates
      case req @ POST -> Root / "api" / "flights" / "search" / "stream" =>
        req.as[SearchFlightsRequest].flatMap { searchReq =>
          getSessionId(req) match {
            case None =>
              IO.pure(org.http4s.Response[IO](Status.Unauthorized)
                .withEntity(ErrorResponse("Missing or invalid session.").asJson))
            case Some(sessionId) =>
              sessions.get.flatMap(_.get(sessionId).filterNot(_.isExpired) match {
                case None =>
                  IO.pure(org.http4s.Response[IO](Status.Unauthorized)
                    .withEntity(ErrorResponse("Missing or invalid session.").asJson))
                case Some(data) =>
                  val origin = searchReq.originAirport.getOrElse(data.config.originAirport)
                  val skipCache = searchReq.skipCache.getOrElse(false)
                  val maxResults = searchReq.maxResults

                  // Determine which buckets to search
                  val bucketsToSearch = (searchReq.destinationAirport, searchReq.departureDate) match {
                    case (Some(dest), Some(depDate)) =>
                      val retDate = searchReq.returnDate.getOrElse(depDate.plusDays(data.config.tripDurationDays))
                      List((dest, depDate, retDate))
                    case (Some(dest), None) =>
                      data.filteredBuckets
                        .filter(_.key.airport.code.equalsIgnoreCase(dest))
                        .map(b => (b.key.airport.code, b.key.weekendStart, b.key.weekendStart.plusDays(data.config.tripDurationDays)))
                    case (None, Some(depDate)) =>
                      data.filteredBuckets
                        .filter(_.key.weekendStart == depDate)
                        .map(b => (b.key.airport.code, depDate, searchReq.returnDate.getOrElse(depDate.plusDays(data.config.tripDurationDays))))
                    case (None, None) =>
                      data.filteredBuckets
                        .map(b => (b.key.airport.code, b.key.weekendStart, b.key.weekendStart.plusDays(data.config.tripDurationDays)))
                  }

                  val limitedBuckets = maxResults.fold(bucketsToSearch)(n => bucketsToSearch.take(n))
                  val total = limitedBuckets.size

                  // Create SSE stream with real-time progress events
                  val sseStream: Stream[IO, String] = Stream.eval(
                    for {
                      keyManager <- ApiKeyManager.create(data.apiKeys)
                      resultsRef <- Ref.of[IO, List[FlightSearchResult]](List.empty)
                    } yield (keyManager, resultsRef)
                  ).flatMap { case (keyManager, resultsRef) =>
                    val flightsClient = new FlightsClient(backend, keyManager, cacheDir, CACHE_TTL_SECONDS)

                    // Stream progress events as each flight is fetched
                    val progressStream = Stream.emits(limitedBuckets.zipWithIndex).evalMap { case ((dest, depDate, retDate), idx) =>
                      flightsClient.roundTripOptions(origin, dest, depDate, retDate, skipCache).flatMap { case (quotes, cacheInfo) =>
                        val result = FlightSearchResult(
                          origin = origin,
                          destination = dest,
                          departureDate = depDate,
                          returnDate = retDate,
                          quotes = quotes.sortBy(_.priceUsd),
                          cacheInfo = toQuoteCacheInfo(cacheInfo)
                        )
                        val progress = SearchProgress(
                          current = idx + 1,
                          total = total,
                          destination = dest,
                          departureDate = depDate,
                          fromCache = cacheInfo.fromCache,
                          priceUsd = quotes.sortBy(_.priceUsd).headOption.map(_.priceUsd)
                        )
                        resultsRef.update(_ :+ result).map { _ =>
                          s"event: progress\ndata: ${progress.asJson.noSpaces}\n\n"
                        }
                      }
                    }

                    // After all progress, emit complete event and update session
                    val completeStream = Stream.eval {
                      resultsRef.get.flatMap { results =>
                        val newQuotes = results.flatMap { result =>
                          val bucket = data.allBuckets.find(b =>
                            b.key.airport.code == result.destination &&
                            b.key.weekendStart == result.departureDate
                          )
                          bucket.map { b =>
                            WeekendQuote(b, result.quotes, result.cacheInfo)
                          }
                        }

                        val existingQuotesMap = data.weekendQuotes.map(q => (q.bucket.key.airport.code, q.bucket.key.weekendStart) -> q).toMap
                        val newQuotesMap = newQuotes.map(q => (q.bucket.key.airport.code, q.bucket.key.weekendStart) -> q).toMap
                        val mergedQuotes = (existingQuotesMap ++ newQuotesMap).values.toList
                        val updatedData = data.copy(weekendQuotes = mergedQuotes)

                        sessions.update(_ + (sessionId -> updatedData)).map { _ =>
                          val complete = SearchComplete(results, results.map(_.quotes.size).sum)
                          s"event: complete\ndata: ${complete.asJson.noSpaces}\n\n"
                        }
                      }
                    }

                    progressStream ++ completeStream
                  }.handleErrorWith { err =>
                    val errorEvent = s"event: error\ndata: ${SearchError(err.getMessage).asJson.noSpaces}\n\n"
                    Stream.emit(errorEvent)
                  }

                  val sseMediaType = MediaType.`text/event-stream`
                  Ok(sseStream).map(_.withContentType(`Content-Type`(sseMediaType)))
              })
          }
        }

      // GET /api/flights/quotes - Get cached quotes with optional filters
      case req @ GET -> Root / "api" / "flights" / "quotes"
          :? AirportParam(airportOpt)
          +& StateParam(stateOpt)
          +& MaxPriceParam(maxPriceOpt)
          +& SearchParam(searchOpt)
          +& LimitParam(limitOpt) =>
        withSession(req) { data =>
          var quotes = data.weekendQuotes

          // Apply filters
          quotes = airportOpt.fold(quotes)(code => FlightFilterService.filterByAirport(quotes, code))
          quotes = stateOpt.fold(quotes)(st => FlightFilterService.filterByState(quotes, st))
          quotes = maxPriceOpt.fold(quotes)(price => FlightFilterService.filterByMaxPrice(quotes, price))
          quotes = searchOpt.fold(quotes)(term => FlightFilterService.filterByTournamentName(quotes, term))

          // Sort and limit
          val sorted = FlightFilterService.sortByPrice(quotes)
          val limited = limitOpt.fold(sorted)(n => sorted.take(n))

          Ok(QuotesResponse(limited.size, limited).asJson)
        }

      // ─────────────────────────────────────────────────────────────────────────
      // Data Endpoints
      // ─────────────────────────────────────────────────────────────────────────

      // GET /api/airports - List all destination airports
      case req @ GET -> Root / "api" / "airports" =>
        withSession(req) { data =>
          val airports = data.allBuckets.map(_.key.airport.code).distinct.sorted
          Ok(AirportsResponse(airports).asJson)
        }

      // GET /api/states - List all states/regions
      case req @ GET -> Root / "api" / "states" =>
        withSession(req) { data =>
          val states = data.tournaments.map(_.stateOrRegion).distinct.sorted
          Ok(StatesResponse(states).asJson)
        }

      // GET /api/tournaments - List all tournament names
      case req @ GET -> Root / "api" / "tournaments" =>
        withSession(req) { data =>
          val names = data.tournaments.map(_.name).distinct.sorted
          Ok(TournamentsResponse(names).asJson)
        }

      // GET /api/buckets - List weekend buckets (filtered by session config)
      case req @ GET -> Root / "api" / "buckets" =>
        withSession(req) { data =>
          val buckets = TournamentService.sortBuckets(data.filteredBuckets)
          Ok(Json.obj(
            "count" -> Json.fromInt(buckets.size),
            "buckets" -> buckets.asJson
          ))
        }

      // ─────────────────────────────────────────────────────────────────────────
      // API Key Management
      // ─────────────────────────────────────────────────────────────────────────

      // GET /api/keys/usage - Get API key usage statistics
      case req @ GET -> Root / "api" / "keys" / "usage" =>
        withSession(req) { data =>
          data.apiKeys.traverse { key =>
            AccountClient.fetchAccountInfo(backend, key).map { result =>
              val maskedKey = data.maskKey(key)
              result match {
                case Right(info) =>
                  ApiKeyUsage(
                    maskedKey = maskedKey,
                    accountEmail = Some(info.accountEmail),
                    planName = Some(info.planName),
                    searchesPerMonth = Some(info.searchesPerMonth),
                    thisMonthUsage = Some(info.thisMonthUsage),
                    planSearchesLeft = Some(info.planSearchesLeft),
                    extraCredits = Some(info.extraCredits),
                    totalSearchesLeft = Some(info.totalSearchesLeft),
                    error = None
                  )
                case Left(err) =>
                  ApiKeyUsage(
                    maskedKey = maskedKey,
                    accountEmail = None,
                    planName = None,
                    searchesPerMonth = None,
                    thisMonthUsage = None,
                    planSearchesLeft = None,
                    extraCredits = None,
                    totalSearchesLeft = None,
                    error = Some(err)
                  )
              }
            }
          }.flatMap(usages => Ok(ApiKeyUsageResponse(usages).asJson))
        }
    }
  }
}
