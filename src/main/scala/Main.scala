import cats.effect.IO
import cats.effect.IOApp
import cats.syntax.traverse._
import flights.FlightsClient
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import models._
import scala.jdk.CollectionConverters._
import scala.util.Try
import scraper.TournamentScraper
import sttp.client3._
import sttp.client3.httpclient.cats.HttpClientCatsBackend

object Main extends IOApp.Simple {
  private val omnipongUrl = uri"https://omnipong.com/t-tourney.asp?e=0"

  private val serpTimeFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

  private val fileTimestampFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

  private final case class DepartureWindow(
      earliest: Option[LocalTime],
      latest: Option[LocalTime]
  )

  private final case class AppConfig(
      apiKey: String,
      originAirport: String,
      friendAirports: Set[String],
      maxApiCallsPerRun: Int,
      filterMonths: Long,
      maxPriceBase: Int,
      maxPriceFriend: Int,
      outboundWindow: DepartureWindow,
      returnWindow: DepartureWindow
  )

  private def weekendKey(d: LocalDate): LocalDate = d.`with`(TemporalAdjusters.previousOrSame(DayOfWeek.FRIDAY))

  private def toWeekendBuckets(
      tournaments: List[Tournament]
  ): List[WeekendBucket] = {
    val withMetros = tournaments.flatMap { t =>
      MetroMapping.airportFor(t.city, t.stateOrRegion).map(a => (t, a))
    }

    withMetros
      .groupBy { case (t, a) => WeekendKey(a, weekendKey(t.startDate)) }
      .toList
      .map { case (key, list) => WeekendBucket(key, list.map(_._1)) }
  }

  private def filterNextMonths(
      buckets: List[WeekendBucket],
      months: Long
  ): List[WeekendBucket] = {
    val today = LocalDate.now()
    val cutoff = today.plusMonths(months)

    buckets.filter { b =>
      val d = b.key.weekendStart
      !d.isBefore(today) && !d.isAfter(cutoff)
    }
  }

  private def parseLocalTimeFromFlightString(s: String): Option[LocalTime] =
    if (s == null || s.isEmpty) None
    else
      try
        Some(LocalDateTime.parse(s, serpTimeFormat).toLocalTime)
      catch {
        case _: Throwable => None
      }

  private def withinWindow(timeStr: String, window: DepartureWindow): Boolean = {
    if (window.earliest.isEmpty && window.latest.isEmpty) return true

    parseLocalTimeFromFlightString(timeStr) match {
      case None => true
      case Some(t) =>
        window.earliest.forall(!t.isBefore(_)) &&
        window.latest.forall(!t.isAfter(_))
    }
  }

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

  private def writeMarkdownReport(
      prefix: String,
      title: String,
      list: List[WeekendQuote]
  ): IO[Path] =
    IO {
      val generatedAt = LocalDateTime.now()
      val generatedAtStr =
        generatedAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
      val timestamp = generatedAt.format(fileTimestampFormat)
      val fileName = s"${prefix}_${timestamp}.md"

      val headerLines = List(
        s"# $title",
        "",
        s"_Generated at: $generatedAtStr",
        "",
        "| Airport | Weekend | Price | Friend | Outbound Departure | Outbound Arrival | Return Departure | Return Arrival | Tournaments |",
        "|--------|---------|-------|--------|---------------------|------------------|------------------|----------------|------------|"
      )

      val rows = list.map { wq =>
        val code = wq.bucket.key.airport.code
        val wk = wq.bucket.key.weekendStart.toString
        val friend = if (wq.isFriendAirport) "yes" else "no"

        val (priceStr, outDep, outArr, retDep, retArr) = wq.quote match {
          case Some(q) =>
            (
              s"$$${q.priceUsd}",
              Option(q.outboundDepartureTime).getOrElse(""),
              Option(q.outboundArrivalTime).getOrElse(""),
              Option(q.returnDepartureTime).getOrElse(""),
              Option(q.returnArrivalTime).getOrElse("")
            )
          case None =>
            ("no-price", "", "", "", "")
        }

        val ts = wq.bucket.tournaments
          .map(t => s"${t.name} (${t.city}, ${t.stateOrRegion}, ${t.rawDateText})")
          .mkString("<br>")

        s"| $code | $wk | $priceStr | $friend | $outDep | $outArr | $retDep | $retArr | $ts |"
      }

      val full = (headerLines ++ rows).mkString("\n")
      val path = Paths.get(fileName)
      Files.write(path, full.getBytes(StandardCharsets.UTF_8))
      path
    }

  private def fetchWeekendQuotes(
      client: FlightsClient,
      buckets: List[WeekendBucket],
      config: AppConfig
  ): IO[List[WeekendQuote]] = {
    val sorted = buckets.sortBy(b => (b.key.weekendStart, b.key.airport.code))
    val toCall = sorted.take(config.maxApiCallsPerRun)

    toCall.traverse { bucket =>
      val depart = bucket.key.weekendStart
      val ret = depart.plusDays(2)

      IO.println(
        s"Querying flights for ${config.originAirport} -> ${bucket.key.airport.code} ($depart to $ret)"
      ) *>
        client
          .roundTripOptions(
            config.originAirport,
            bucket.key.airport.code,
            depart,
            ret
          )
          .map { quotes =>
            val isFriend =
              config.friendAirports.contains(bucket.key.airport.code)
            val cheapest = quotes.sortBy(_.priceUsd).headOption
            WeekendQuote(bucket, cheapest, isFriend)
          }
    }
  }

  private def readEnvFile(path: Path): Map[String, String] =
    if (!Files.exists(path)) Map.empty
    else {
      val lines = Files.readAllLines(path, StandardCharsets.UTF_8).asScala.toList
      lines
        .map(_.trim)
        .filter(l => l.nonEmpty && !l.startsWith("#"))
        .flatMap { line =>
          val idx = line.indexOf('=')
          if (idx > 0) {
            val key = line.substring(0, idx).trim
            val value = line.substring(idx + 1).trim
            if (key.nonEmpty) Some(key -> value) else None
          } else None
        }
        .toMap
    }

  private def parseTimeConfig(value: String): Option[LocalTime] =
    if (value == null || value.trim.isEmpty) None
    else Try(LocalTime.parse(value.trim)).toOption

  private def loadConfig(): AppConfig = {
    val fileEnv = readEnvFile(Paths.get(".env"))
    val env: Map[String, String] = sys.env ++ fileEnv

    def get(key: String): Option[String] = env.get(key).map(_.trim).filter(_.nonEmpty)

    val apiKey =
      get("SERPAPI_KEY").getOrElse("KEYHERE")

    val originAirport =
      get("ORIGIN_AIRPORT").getOrElse("ORD")

    val friendAirports =
      get("FRIEND_AIRPORTS")
        .map(_.split(",").toList.map(_.trim).filter(_.nonEmpty).toSet)
        .getOrElse(Set("BOS", "AUS", "LAX"))

    val maxApiCallsPerRun =
      get("MAX_API_CALLS_PER_RUN")
        .flatMap(v => Try(v.toInt).toOption)
        .getOrElse(150)

    val filterMonths =
      get("FILTER_MONTHS")
        .flatMap(v => Try(v.toLong).toOption)
        .getOrElse(3L)

    val maxPriceBase =
      get("MAX_PRICE_BASE")
        .flatMap(v => Try(v.toInt).toOption)
        .getOrElse(150)

    val maxPriceFriend =
      get("MAX_PRICE_FRIEND")
        .flatMap(v => Try(v.toInt).toOption)
        .getOrElse(250)

    val outboundWindow = DepartureWindow(
      earliest = get("OUTBOUND_EARLIEST").flatMap(parseTimeConfig),
      latest = get("OUTBOUND_LATEST").flatMap(parseTimeConfig)
    )

    val returnWindow = DepartureWindow(
      earliest = get("RETURN_EARLIEST").flatMap(parseTimeConfig),
      latest = get("RETURN_LATEST").flatMap(parseTimeConfig)
    )

    AppConfig(
      apiKey = apiKey,
      originAirport = originAirport,
      friendAirports = friendAirports,
      maxApiCallsPerRun = maxApiCallsPerRun,
      filterMonths = filterMonths,
      maxPriceBase = maxPriceBase,
      maxPriceFriend = maxPriceFriend,
      outboundWindow = outboundWindow,
      returnWindow = returnWindow
    )
  }

  def run: IO[Unit] =
    HttpClientCatsBackend.resource[IO]().use { backend =>
      val config = loadConfig()

      val flightsClient =
        new FlightsClient(backend, config.apiKey, Paths.get("flight_cache"))

      for {
        response <- basicRequest.get(omnipongUrl).send(backend)
        body <- IO.fromEither(response.body.left.map(new Exception(_)))
        tournaments = TournamentScraper.parseTournaments(body)
        _ <- IO.println(s"Parsed ${tournaments.size} tournaments")

        weekendBucketsAll = toWeekendBuckets(tournaments)
        _ <- IO.println(
               s"Total metro/weekend buckets (all): ${weekendBucketsAll.size}"
             )

        weekendBuckets =
          filterNextMonths(weekendBucketsAll, config.filterMonths)
        _ <- IO.println(
               s"Buckets within next ${config.filterMonths} months: ${weekendBuckets.size}"
             )

        weekendQuotes <- fetchWeekendQuotes(flightsClient, weekendBuckets, config)
        _ <-
          IO.println(
            s"Weekend buckets with flight data attempted (capped at ${config.maxApiCallsPerRun}): ${weekendQuotes.size}\n"
          )

        filteredWeekends = weekendQuotes.collect {
                             case wq @ WeekendQuote(_, Some(q), _)
                                 if (q.priceUsd <= config.maxPriceBase ||
                                   (wq.isFriendAirport && q.priceUsd <= config.maxPriceFriend)) &&
                                   withinWindow(
                                     q.outboundDepartureTime,
                                     config.outboundWindow
                                   ) &&
                                   withinWindow(
                                     q.returnDepartureTime,
                                     config.returnWindow
                                   ) =>
                               wq
                           }

        _ <- IO.println("\n=== All weekend quotes (queried) ===\n")
        _ <- IO.println(formatTable(weekendQuotes))

        _ <- IO.println(
               s"\n=== Filtered weekends ===\nCount: ${filteredWeekends.size}\n"
             )
        _ <- IO.println(formatTable(filteredWeekends))

        allMdPath <- writeMarkdownReport(
                       prefix = "all_flights",
                       title = "All Flight Quotes",
                       list = weekendQuotes
                     )
        _ <- IO.println(s"\nMarkdown written to $allMdPath")

        idealMdPath <- writeMarkdownReport(
                         prefix = "ideal_flights",
                         title = "Ideal Flight Quotes",
                         list = filteredWeekends
                       )
        _ <- IO.println(s"Markdown written to $idealMdPath")
      } yield ()
    }
}
