package flights

import cats.effect.IO
import io.circe.parser
import io.circe.Json
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.time.{Instant, LocalDate}
import models.FlightQuote
import sttp.client3._
import sttp.model.StatusCode

case class CacheInfo(
    fromCache: Boolean,
    cacheAgeSeconds: Option[Long],
    cachedAt: Option[Instant]
)

object CacheInfo {
  val fresh: CacheInfo = CacheInfo(fromCache = false, cacheAgeSeconds = None, cachedAt = None)
  def cached(ageSeconds: Long, cachedAt: Instant): CacheInfo = 
    CacheInfo(fromCache = true, cacheAgeSeconds = Some(ageSeconds), cachedAt = Some(cachedAt))
}

final class FlightsClient(
    backend: SttpBackend[IO, Any],
    keyManager: ApiKeyManager,
    cacheDir: Path,
    cacheTtlSeconds: Long = 24 * 60 * 60
) {
  private val baseUri = uri"https://serpapi.com/search.json"

  private def cacheKey(
      origin: String,
      dest: String,
      depart: LocalDate,
      ret: LocalDate
  ): String = s"${origin}_${dest}_${depart}_${ret}.json"

  private def getCacheAge(path: Path): IO[Option[(Long, Instant)]] =
    IO {
      if (Files.exists(path)) {
        val attrs = Files.readAttributes(path, classOf[BasicFileAttributes])
        val modifiedTime = attrs.lastModifiedTime().toInstant
        val ageSeconds = Instant.now().getEpochSecond - modifiedTime.getEpochSecond
        Some((ageSeconds, modifiedTime))
      } else None
    }

  private def loadCachedJson(path: Path, skipCache: Boolean): IO[Option[(Json, CacheInfo)]] =
    if (skipCache) IO.pure(None)
    else IO {
      if (Files.exists(path)) {
        val attrs = Files.readAttributes(path, classOf[BasicFileAttributes])
        val modifiedTime = attrs.lastModifiedTime().toInstant
        val ageSeconds = Instant.now().getEpochSecond - modifiedTime.getEpochSecond
        
        if (ageSeconds > cacheTtlSeconds) {
          Files.delete(path)
          None
        } else {
          val bytes = Files.readAllBytes(path)
          parser.parse(new String(bytes, StandardCharsets.UTF_8)).toOption.map { json =>
            (json, CacheInfo.cached(ageSeconds, modifiedTime))
          }
        }
      } else None
    }

  private def saveCachedJson(path: Path, json: Json): IO[Unit] =
    IO {
      Files.createDirectories(path.getParent)
      Files.write(path, json.noSpaces.getBytes(StandardCharsets.UTF_8))
      ()
    }

  private def extractFlightQuotes(
      json: Json,
      origin: String,
      dest: String,
      depart: LocalDate,
      ret: LocalDate
  ): List[FlightQuote] = {
    val cursor = json.hcursor
    
    val googleFlightsUrl = cursor
      .downField("search_metadata")
      .downField("google_flights_url")
      .as[String]
      .toOption

    def fromField(field: String): List[FlightQuote] =
      cursor
        .downField(field)
        .as[List[Json]]
        .getOrElse(Nil)
        .flatMap { fJson =>
          val c = fJson.hcursor
          val priceOpt = c.downField("price").as[Int].toOption
          val flights = c.downField("flights").as[List[Json]].getOrElse(Nil)

          (priceOpt, flights.headOption, flights.lastOption) match {
            case (Some(price), Some(first), Some(last)) =>
              val fc = first.hcursor
              val lc = last.hcursor

              val outDep = fc
                .downField("departure_airport")
                .downField("time")
                .as[String]
                .getOrElse("")
              val outArr = lc
                .downField("arrival_airport")
                .downField("time")
                .as[String]
                .getOrElse("")
              val airline = fc
                .downField("airline")
                .as[String]
                .getOrElse("")

              Some(
                FlightQuote(
                  origin = origin,
                  destination = dest,
                  departureDate = depart,
                  returnDate = ret,
                  priceUsd = BigDecimal(price),
                  outboundDepartureTime = outDep,
                  outboundArrivalTime = outArr,
                  airline = airline,
                  googleFlightsUrl = googleFlightsUrl
                )
              )
            case _ => None
          }
        }

    fromField("best_flights") ++ fromField("other_flights")
  }

  def roundTripOptions(
      origin: String,
      dest: String,
      depart: LocalDate,
      ret: LocalDate,
      skipCache: Boolean = false
  ): IO[(List[FlightQuote], CacheInfo)] = {
    val path = cacheDir.resolve(cacheKey(origin, dest, depart, ret))

    def fetchFromApiWithKey(apiKey: String): IO[Either[Int, Json]] = {
      val req = basicRequest
        .get(
          baseUri
            .addParam("engine", "google_flights")
            .addParam("departure_id", origin)
            .addParam("arrival_id", dest)
            .addParam("outbound_date", depart.toString)
            .addParam("return_date", ret.toString)
            .addParam("type", "1")
            .addParam("currency", "USD")
            .addParam("hl", "en")
            .addParam("api_key", apiKey)
        )
        .response(asStringAlways)

      req.send(backend).flatMap { resp =>
        if (resp.code == StatusCode.TooManyRequests) {
          IO.pure(Left(429))
        } else if (!resp.code.isSuccess) {
          IO.println(s"SerpAPI error (${resp.code}): ${resp.body}") *> IO.pure(Right(Json.Null))
        } else {
          parser.parse(resp.body) match {
            case Left(pe) =>
              IO.println(s"JSON parse error: $pe") *> IO.pure(Right(Json.Null))
            case Right(json) =>
              saveCachedJson(path, json) *> IO.pure(Right(json))
          }
        }
      }
    }

    def fetchWithRetry: IO[Json] =
      for {
        apiKey <- keyManager.currentKey
        keyIdx <- keyManager.currentIndex
        _      <- IO.println(s"Using API key ${keyIdx + 1}/${keyManager.keyCount}")
        result <- fetchFromApiWithKey(apiKey)
        json   <- result match {
                    case Right(j) => IO.pure(j)
                    case Left(429) =>
                      keyManager.rotateToNext.flatMap {
                        case true =>
                          IO.println("Rate limited (429), rotating to next API key...") *> fetchWithRetry
                        case false =>
                          IO.println("All API keys exhausted (429 on all). Returning empty.") *> IO.pure(Json.Null)
                      }
                    case Left(other) =>
                      IO.println(s"Unexpected error code: $other") *> IO.pure(Json.Null)
                  }
      } yield json

    for {
      cached <- loadCachedJson(path, skipCache)
      result <- cached match {
                  case Some((json, cacheInfo)) =>
                    IO.println(
                      s"Using cached flights for $origin -> $dest ($depart to $ret) [age: ${cacheInfo.cacheAgeSeconds.getOrElse(0)}s]"
                    ) *> IO.pure((json, cacheInfo))
                  case None =>
                    IO.println(
                      s"No cache for $origin -> $dest ($depart to $ret), calling SerpAPI"
                    ) *> fetchWithRetry.map(json => (json, CacheInfo.fresh))
                }
      (json, cacheInfo) = result
      quotes = extractFlightQuotes(json, origin, dest, depart, ret)
    } yield (quotes, cacheInfo)
  }

  def cheapestRoundTrip(
      origin: String,
      dest: String,
      depart: LocalDate,
      ret: LocalDate,
      skipCache: Boolean = false
  ): IO[Option[FlightQuote]] = 
    roundTripOptions(origin, dest, depart, ret, skipCache).map(_._1.sortBy(_.priceUsd).headOption)
}
