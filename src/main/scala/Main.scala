import cats.effect.IO
import cats.effect.IOApp
import cats.syntax.traverse._
import flights.FlightsClient
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.time.format.DateTimeFormatter
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import models._
import scraper.TournamentScraper
import sttp.client3._
import sttp.client3.httpclient.cats.HttpClientCatsBackend

object Main extends IOApp.Simple {
  private val omnipongUrl = uri"https://omnipong.com/t-tourney.asp?e=0"
  private val originAirport = "ORD"

  // airports where you have friends / bonus cities
  private val friendAirports: Set[String] = Set("BOS", "AUS", "LAX")

  // safety cap on SerpAPI calls per weekly run
  private val maxApiCallsPerRun = 150

  // SerpAPI time format: "2023-10-03 15:10"
  private val serpTimeFmt: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

  // Configurable departure windows (local time at that airport)
  final case class DepartureWindow(
      earliest: Option[LocalTime],
      latest: Option[LocalTime]
  )

  // Default: no restrictions (anything is acceptable)
  private val outboundWindow: DepartureWindow = DepartureWindow(
//    earliest = Some(LocalTime.of(18, 0)),
    earliest = None,
    latest = None
  )
  private val returnWindow: DepartureWindow = DepartureWindow(
    earliest = None,
//    latest = Some(LocalTime.of(21, 0))
    latest = None
  )

  private def weekendKey(d: LocalDate): LocalDate = {
    var date = d
    while (date.getDayOfWeek != DayOfWeek.FRIDAY)
      date = date.minusDays(1)
    date
  }

  private def toWeekendBuckets(
      tournaments: List[Tournament]
  ): List[WeekendBucket] = {
    val withMetros = tournaments.flatMap { t =>
      MetroMapping.airportFor(t.city, t.stateOrRegion).map(a => (t, a))
    }

    val grouped = withMetros.groupBy {
      case (t, a) =>
        WeekendKey(a, weekendKey(t.startDate))
    }

    grouped.toList.map {
      case (key, list) =>
        WeekendBucket(key, list.map(_._1))
    }
  }

  /** Keep only buckets whose weekendStart is in the next 3 months. */
  private def filterNextThreeMonths(
      buckets: List[WeekendBucket]
  ): List[WeekendBucket] = {
    val today = LocalDate.now()
    val cutoff = today.plusMonths(3)

    buckets.filter { b =>
      val d = b.key.weekendStart
      !d.isBefore(today) && !d.isAfter(cutoff)
    }
  }

  private def parseLocalTimeOrNone(s: String): Option[LocalTime] =
    if (s == null || s.isEmpty) None
    else
      try
        Some(LocalDateTime.parse(s, serpTimeFmt).toLocalTime)
      catch {
        case _: Throwable => None
      }

  private def withinWindow(timeStr: String, window: DepartureWindow): Boolean = {
    // no restriction → always ok
    if (window.earliest.isEmpty && window.latest.isEmpty) return true

    parseLocalTimeOrNone(timeStr) match {
      case None => true // if we can't parse, don't reject the flight
      case Some(t) =>
        window.earliest.forall(!t.isBefore(_)) &&
        window.latest.forall(!t.isAfter(_))
    }
  }

  /** Format a list of WeekendQuote into an ASCII table. */
  private def formatTable(list: List[WeekendQuote]): String = {
    val header =
      f"${"Airport"}%-7s | ${"Weekend"}%-12s | ${"Price"}%-8s | ${"Friend"}%-6s | ${"Out Dep"}%-16s | ${"Out Arr"}%-16s | ${"Ret Dep"}%-16s | ${"Ret Arr"}%-16s | Tournaments"

    val rows = list.map { wq =>
      val code = wq.bucket.key.airport.code
      val wk = wq.bucket.key.weekendStart.toString
      val friend = if (wq.isFriendAirport) "yes" else "no"

      val (priceStr, outDep, outArr, retDep, retArr) = wq.quote match {
        case Some(q) =>
          (
            s"$$${q.priceUsd}",
            q.outboundDepartureTime,
            q.outboundArrivalTime,
            q.returnDepartureTime,
            q.returnArrivalTime
          )
        case None =>
          ("no-price", "", "", "", "")
      }

      val ts = wq.bucket.tournaments
        .map(t => s"${t.name} (${t.city}, ${t.stateOrRegion}, ${t.rawDateText})")
        .mkString("; ")

      f"$code%-7s | $wk%-12s | $priceStr%-8s | $friend%-6s | $outDep%-16s | $outArr%-16s | $retDep%-16s | $retArr%-16s | $ts"
    }

    (header +: rows).mkString("\n")
  }

  /** Write data to CSV, including departure/arrival times both ways. */
  private def writeCsv(list: List[WeekendQuote], path: String): IO[Unit] =
    IO {
      val header =
        "airport,weekend,price,friend,outbound_departure,outbound_arrival,return_departure,return_arrival,tournaments"

      val rows = list.map { wq =>
        val code = wq.bucket.key.airport.code
        val wk = wq.bucket.key.weekendStart.toString
        val friend = if (wq.isFriendAirport) "yes" else "no"

        val (priceStr, outDep, outArr, retDep, retArr) = wq.quote match {
          case Some(q) =>
            (
              q.priceUsd.toString,
              q.outboundDepartureTime,
              q.outboundArrivalTime,
              q.returnDepartureTime,
              q.returnArrivalTime
            )
          case None =>
            ("", "", "", "", "")
        }

        val ts = wq.bucket.tournaments
          .map(t => s"${t.name} (${t.city}, ${t.stateOrRegion}, ${t.rawDateText})")
          .mkString(" | ")

        // crude CSV escaping (good enough for this use)
        s"$code,$wk,$priceStr,$friend,\"$outDep\",\"$outArr\",\"$retDep\",\"$retArr\",\"$ts\""
      }

      val full = (header +: rows).mkString("\n")
      Files.write(Paths.get(path), full.getBytes(StandardCharsets.UTF_8))
    }

  /** Call SerpAPI for at most `maxApiCallsPerRun` buckets. Returns only the buckets that were actually queried.
    */
  private def fetchWeekendQuotes(
      client: FlightsClient,
      buckets: List[WeekendBucket]
  ): IO[List[WeekendQuote]] = {
    val sorted = buckets.sortBy(b => (b.key.weekendStart, b.key.airport.code))
    val toCall = sorted.take(maxApiCallsPerRun)

    toCall.traverse { bucket =>
      val depart = bucket.key.weekendStart
      val ret = depart.plusDays(2)

      IO.println(
        s"Querying flights for $originAirport -> ${bucket.key.airport.code} ($depart to $ret)"
      ) *>
        client
          .roundTripOptions(originAirport, bucket.key.airport.code, depart, ret)
          .map { quotes =>
            val isFriend = friendAirports.contains(bucket.key.airport.code)
            val cheapest = quotes.sortBy(_.priceUsd).headOption
            WeekendQuote(bucket, cheapest, isFriend)
          }
    }
  }

  def run: IO[Unit] =
    HttpClientCatsBackend.resource[IO]().use { backend =>
      val apiKey = sys.env.getOrElse("SERPAPI_KEY", "KEYHERE")
      val flightsClient =
        new FlightsClient(backend, apiKey, Paths.get("flight_cache"))

      for {
        // scrape tournaments
        response <- basicRequest.get(omnipongUrl).send(backend)
        body <- IO.fromEither(response.body.left.map(new Exception(_)))
        tournaments = TournamentScraper.parseTournaments(body)
        _ <- IO.println(s"Parsed ${tournaments.size} tournaments")

        weekendBucketsAll = toWeekendBuckets(tournaments)
        _ <- IO.println(s"Total metro/weekend buckets (all): ${weekendBucketsAll.size}")

        weekendBuckets = filterNextThreeMonths(weekendBucketsAll)
        _ <- IO.println(s"Buckets within next 3 months: ${weekendBuckets.size}")

        // all weekends that were actually queried
        weekendQuotes <- fetchWeekendQuotes(flightsClient, weekendBuckets)
        _ <- IO.println(
               s"Weekend buckets with flight data attempted (capped at $maxApiCallsPerRun): ${weekendQuotes.size}\n"
             )

        // in-memory cheap subset with time window filters
        filteredWeekends = weekendQuotes.collect {
                             case wq @ WeekendQuote(_, Some(q), _)
                                 if (q.priceUsd <= 100 || (wq.isFriendAirport && q.priceUsd <= 200)) &&
                                   withinWindow(q.outboundDepartureTime, outboundWindow) &&
                                   withinWindow(q.returnDepartureTime, returnWindow) =>
                               wq
                           }

        // table: all queried weekends
        _ <- IO.println("\n=== All weekend quotes (queried) ===\n")
        _ <- IO.println(formatTable(weekendQuotes))

        // table: cheap subset
        _ <- IO.println(s"\n=== Filtered weekends ===\nCount: ${filteredWeekends.size}\n")
        _ <- IO.println(formatTable(filteredWeekends))

        // CSV output (all queried weekends)
        _ <- writeCsv(weekendQuotes, "weekend_quotes.csv")
        _ <- IO.println("\nCSV written to weekend_quotes.csv")
      } yield ()
    }
}
