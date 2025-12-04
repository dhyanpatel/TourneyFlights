package api

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.syntax.traverse._
import config.AppConfig
import flights.{ApiKeyManager, FlightsClient}
import io.circe._
import io.circe.generic.semiauto._
import io.circe.syntax._
import models._
import org.http4s._
import org.http4s.circe._
import org.http4s.dsl.io._
import org.typelevel.ci.CIString
import scraper.TournamentScraper
import services.{FlightFilterService, TournamentService}
import sttp.client3.{SttpBackend => _, Response => _, Request => _, _}
import sttp.client3.SttpBackend
import java.time.{Instant, LocalDate}
import java.util.UUID

object ApiRoutes {

  // ─────────────────────────────────────────────────────────────────────────────
  // JSON Encoders
  // ─────────────────────────────────────────────────────────────────────────────

  implicit val localDateEncoder: Encoder[LocalDate] = Encoder.encodeString.contramap(_.toString)

  implicit val metroAirportEncoder: Encoder[MetroAirport] = Encoder.encodeString.contramap(_.code)

  implicit val tournamentEncoder: Encoder[Tournament] = deriveEncoder[Tournament]

  implicit val weekendKeyEncoder: Encoder[WeekendKey] = deriveEncoder[WeekendKey]

  implicit val weekendBucketEncoder: Encoder[WeekendBucket] = deriveEncoder[WeekendBucket]

  implicit val flightQuoteEncoder: Encoder[FlightQuote] = deriveEncoder[FlightQuote]

  implicit val weekendQuoteEncoder: Encoder[WeekendQuote] = deriveEncoder[WeekendQuote]

  case class SummaryResponse(
      totalTournaments: Int,
      totalBuckets: Int,
      filteredBuckets: Int,
      totalQuotes: Int,
      quotesWithPrice: Int,
      quotesWithoutPrice: Int,
      filteredQuotesCount: Int,
      originAirport: String,
      friendAirports: Set[String]
  )
  implicit val summaryResponseEncoder: Encoder[SummaryResponse] = deriveEncoder[SummaryResponse]

  case class AirportsResponse(airports: List[String])
  implicit val airportsResponseEncoder: Encoder[AirportsResponse] = deriveEncoder[AirportsResponse]

  case class StatesResponse(states: List[String])
  implicit val statesResponseEncoder: Encoder[StatesResponse] = deriveEncoder[StatesResponse]

  case class TournamentsResponse(tournaments: List[String])
  implicit val tournamentsResponseEncoder: Encoder[TournamentsResponse] = deriveEncoder[TournamentsResponse]

  case class QuotesResponse(count: Int, quotes: List[WeekendQuote])
  implicit val quotesResponseEncoder: Encoder[QuotesResponse] = deriveEncoder[QuotesResponse]

  case class ErrorResponse(error: String)
  implicit val errorResponseEncoder: Encoder[ErrorResponse] = deriveEncoder[ErrorResponse]

  // Request body for creating session
  case class CreateSessionRequest(apiKeys: List[String])
  implicit val createSessionRequestDecoder: Decoder[CreateSessionRequest] = deriveDecoder[CreateSessionRequest]

  // Response for session creation
  case class SessionResponse(
      sessionId: String,
      totalTournaments: Int,
      totalBuckets: Int,
      filteredBuckets: Int,
      totalQuotes: Int,
      quotesWithPrice: Int,
      filteredQuotesCount: Int
  )
  implicit val sessionResponseEncoder: Encoder[SessionResponse] = deriveEncoder[SessionResponse]

  // ─────────────────────────────────────────────────────────────────────────────
  // Query Parameter Matchers
  // ─────────────────────────────────────────────────────────────────────────────

  object AirportParam extends OptionalQueryParamDecoderMatcher[String]("airport")
  object StateParam extends OptionalQueryParamDecoderMatcher[String]("state")
  object MaxPriceParam extends OptionalQueryParamDecoderMatcher[Int]("maxPrice")
  object SearchParam extends OptionalQueryParamDecoderMatcher[String]("search")
  object FriendsOnlyParam extends OptionalQueryParamDecoderMatcher[Boolean]("friendsOnly")
  object LimitParam extends OptionalQueryParamDecoderMatcher[Int]("limit")
  object FilteredParam extends OptionalQueryParamDecoderMatcher[Boolean]("filtered")

  private val omnipongUrl = uri"https://omnipong.com/t-tourney.asp?e=0"

  // Session TTL: 1 hour
  private val SESSION_TTL_SECONDS = 3600L

  // ─────────────────────────────────────────────────────────────────────────────
  // Session Data
  // ─────────────────────────────────────────────────────────────────────────────

  case class SessionData(
      tournaments: List[Tournament],
      allBuckets: List[WeekendBucket],
      filteredBuckets: List[WeekendBucket],
      weekendQuotes: List[WeekendQuote],
      filteredQuotes: List[WeekendQuote],
      createdAt: Instant
  ) {
    def isExpired: Boolean =
      Instant.now().getEpochSecond - createdAt.getEpochSecond > SESSION_TTL_SECONDS
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Data Loading Pipeline
  // ─────────────────────────────────────────────────────────────────────────────

  private def loadData(
      backend: SttpBackend[IO, Any],
      config: AppConfig,
      apiKeys: List[String]
  ): IO[SessionData] = {
    for {
      keyManager    <- ApiKeyManager.create(apiKeys)
      flightsClient  = new FlightsClient(backend, keyManager, config.cacheDir)

      response      <- basicRequest.get(omnipongUrl).send(backend)
      body          <- IO.fromEither(response.body.left.map(new Exception(_)))

      tournaments    = TournamentScraper.parseTournaments(body)
      allBuckets     = TournamentService.toWeekendBuckets(tournaments)
      filteredBuckets = TournamentService.filterByDateRange(allBuckets, config.filterMonths)
      sortedBuckets  = TournamentService.sortBuckets(filteredBuckets)

      weekendQuotes <- sortedBuckets.take(config.maxApiCallsPerRun).traverse { bucket =>
        val depart = bucket.key.weekendStart
        val ret    = depart.plusDays(2)
        flightsClient
          .roundTripOptions(config.originAirport, bucket.key.airport.code, depart, ret)
          .map { quotes =>
            val isFriend = config.friendAirports.contains(bucket.key.airport.code)
            val cheapest = quotes.sortBy(_.priceUsd).headOption
            WeekendQuote(bucket, cheapest, isFriend)
          }
      }

      filteredQuotes = FlightFilterService.filterQuotes(weekendQuotes, config)

    } yield SessionData(
      tournaments     = tournaments,
      allBuckets      = allBuckets,
      filteredBuckets = filteredBuckets,
      weekendQuotes   = weekendQuotes,
      filteredQuotes  = filteredQuotes,
      createdAt       = Instant.now()
    )
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Routes
  // ─────────────────────────────────────────────────────────────────────────────

  private val SessionHeader = "X-Session-Id"

  def routes(backend: SttpBackend[IO, Any], config: AppConfig, sessions: Ref[IO, Map[String, SessionData]]): HttpRoutes[IO] = {
    import org.http4s.circe.CirceEntityDecoder._

    def getSession(req: Request[IO]): IO[Option[SessionData]] =
      req.headers.get(CIString(SessionHeader)) match {
        case None => IO.pure(None)
        case Some(header) =>
          sessions.get.map(_.get(header.head.value).filterNot(_.isExpired))
      }

    def withSession(req: Request[IO])(f: SessionData => IO[org.http4s.Response[IO]]): IO[org.http4s.Response[IO]] =
      getSession(req).flatMap {
        case None =>
          IO.pure(org.http4s.Response[IO](Status.Unauthorized).withEntity(ErrorResponse("Missing or invalid session. Call POST /api/session first with your API keys.").asJson))
        case Some(data) =>
          f(data)
      }

    HttpRoutes.of[IO] {

      // POST /api/session - Create session with user-provided API keys
      case req @ POST -> Root / "api" / "session" =>
        req.as[CreateSessionRequest].flatMap { body =>
          if (body.apiKeys.isEmpty) {
            BadRequest(ErrorResponse("apiKeys list cannot be empty").asJson)
          } else {
            loadData(backend, config, body.apiKeys).attempt.flatMap {
              case Right(data) =>
                val sessionId = UUID.randomUUID().toString
                sessions.update(_ + (sessionId -> data)) *>
                  Ok(SessionResponse(
                    sessionId           = sessionId,
                    totalTournaments    = data.tournaments.size,
                    totalBuckets        = data.allBuckets.size,
                    filteredBuckets     = data.filteredBuckets.size,
                    totalQuotes         = data.weekendQuotes.size,
                    quotesWithPrice     = data.weekendQuotes.count(_.quote.isDefined),
                    filteredQuotesCount = data.filteredQuotes.size
                  ).asJson)
              case Left(err) =>
                InternalServerError(ErrorResponse(s"Failed to load data: ${err.getMessage}").asJson)
            }
          }
        }

      // DELETE /api/session - End session
      case req @ DELETE -> Root / "api" / "session" =>
        req.headers.get(CIString(SessionHeader)) match {
          case None =>
            BadRequest(ErrorResponse(s"Missing $SessionHeader header").asJson)
          case Some(header) =>
            sessions.update(_ - header.head.value) *>
              Ok(ErrorResponse("Session ended").asJson)
        }

      // GET /api/summary - Get data summary statistics
      case req @ GET -> Root / "api" / "summary" =>
        withSession(req) { data =>
          Ok(SummaryResponse(
            totalTournaments    = data.tournaments.size,
            totalBuckets        = data.allBuckets.size,
            filteredBuckets     = data.filteredBuckets.size,
            totalQuotes         = data.weekendQuotes.size,
            quotesWithPrice     = data.weekendQuotes.count(_.quote.isDefined),
            quotesWithoutPrice  = data.weekendQuotes.count(_.quote.isEmpty),
            filteredQuotesCount = data.filteredQuotes.size,
            originAirport       = config.originAirport,
            friendAirports      = config.friendAirports
          ).asJson)
        }

      // GET /api/airports - List all destination airports
      case req @ GET -> Root / "api" / "airports" =>
        withSession(req) { data =>
          val airports = data.weekendQuotes.map(_.bucket.key.airport.code).distinct.sorted
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

      // GET /api/quotes - Get quotes with optional filters
      // Query params: airport, state, maxPrice, search, friendsOnly, limit, filtered
      case req @ GET -> Root / "api" / "quotes"
          :? AirportParam(airportOpt)
          +& StateParam(stateOpt)
          +& MaxPriceParam(maxPriceOpt)
          +& SearchParam(searchOpt)
          +& FriendsOnlyParam(friendsOnlyOpt)
          +& LimitParam(limitOpt)
          +& FilteredParam(filteredOpt) =>
        withSession(req) { data =>
          // Start with all quotes or filtered quotes based on param
          val baseQuotes = if (filteredOpt.getOrElse(false)) data.filteredQuotes else data.weekendQuotes

          // Apply filters in sequence
          val afterAirport = airportOpt.fold(baseQuotes)(code =>
            FlightFilterService.filterByAirport(baseQuotes, code)
          )
          val afterState = stateOpt.fold(afterAirport)(st =>
            FlightFilterService.filterByState(afterAirport, st)
          )
          val afterPrice = maxPriceOpt.fold(afterState)(price =>
            FlightFilterService.filterByMaxPrice(afterState, price)
          )
          val afterSearch = searchOpt.fold(afterPrice)(term =>
            FlightFilterService.filterByTournamentName(afterPrice, term)
          )
          val afterFriends = if (friendsOnlyOpt.getOrElse(false))
            FlightFilterService.filterFriendAirportsOnly(afterSearch)
          else afterSearch

          // Sort by price and apply limit
          val sorted = FlightFilterService.sortByPrice(afterFriends)
          val limited = limitOpt.fold(sorted)(n => sorted.take(n))

          Ok(QuotesResponse(limited.size, limited).asJson)
        }
    }
  }
}
