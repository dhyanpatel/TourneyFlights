package flights

import cats.effect.IO
import io.circe.parser
import io.circe.Json
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDate
import models.FlightQuote
import sttp.client3._

final class FlightsClient(
    backend: SttpBackend[IO, Any],
    apiKey: String,
    cacheDir: Path
) {
  private val baseUri = uri"https://serpapi.com/search.json"

  private def cacheKey(
      origin: String,
      dest: String,
      depart: LocalDate,
      ret: LocalDate
  ): String = s"${origin}_${dest}_${depart}_${ret}.json"

  private def loadCachedJson(path: Path): IO[Option[Json]] =
    IO {
      if (Files.exists(path)) {
        val bytes = Files.readAllBytes(path)
        parser.parse(new String(bytes, StandardCharsets.UTF_8)).toOption
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
              val outArr = fc
                .downField("arrival_airport")
                .downField("time")
                .as[String]
                .getOrElse("")
              val retDep = lc
                .downField("departure_airport")
                .downField("time")
                .as[String]
                .getOrElse("")
              val retArr = lc
                .downField("arrival_airport")
                .downField("time")
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
                  returnDepartureTime = retDep,
                  returnArrivalTime = retArr
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
      ret: LocalDate
  ): IO[List[FlightQuote]] = {
    val path = cacheDir.resolve(cacheKey(origin, dest, depart, ret))

    val fetchFromApi: IO[Json] = {
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

      req.send(backend).flatMap { resp =>
        resp.body match {
          case Left(err) =>
            IO.println(s"SerpAPI error: $err") *> IO.pure(Json.Null)
          case Right(b) =>
            parser.parse(b) match {
              case Left(pe) =>
                IO.println(s"JSON parse error: $pe") *> IO.pure(Json.Null)
              case Right(json) =>
                saveCachedJson(path, json) *> IO.pure(json)
            }
        }
      }
    }

    for {
      cached <- loadCachedJson(path)
      json <- cached match {
                case Some(j) =>
                  IO.println(
                    s"Using cached flights for $origin -> $dest ($depart to $ret)"
                  ) *> IO.pure(j)
                case None =>
                  IO.println(
                    s"No cache for $origin -> $dest ($depart to $ret), calling SerpAPI"
                  ) *> fetchFromApi
              }
      quotes = extractFlightQuotes(json, origin, dest, depart, ret)
    } yield quotes
  }

  def cheapestRoundTrip(
      origin: String,
      dest: String,
      depart: LocalDate,
      ret: LocalDate
  ): IO[Option[FlightQuote]] = roundTripOptions(origin, dest, depart, ret).map(_.sortBy(_.priceUsd).headOption)
}
