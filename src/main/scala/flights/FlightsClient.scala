package flights

import cats.effect.IO
import io.circe.parser
import io.circe.Json
import java.time.LocalDate
import models.FlightQuote
import sttp.client3._

final class FlightsClient(
    backend: SttpBackend[IO, Any],
    apiKey: String
) {
  private val baseUri = uri"https://serpapi.com/search.json"

  private def extractMinPrice(json: Json): Option[BigDecimal] = {
    val cursor = json.hcursor

    val bestPrices = cursor
      .downField("best_flights")
      .as[List[Json]]
      .getOrElse(Nil)
      .flatMap { f =>
        f.hcursor.downField("price").as[Int].toOption
      }

    val otherPrices = cursor
      .downField("other_flights")
      .as[List[Json]]
      .getOrElse(Nil)
      .flatMap { f =>
        f.hcursor.downField("price").as[Int].toOption
      }

    val all = bestPrices ++ otherPrices
    all.minOption.map(BigDecimal(_))
  }

  def cheapestRoundTrip(
      origin: String,
      dest: String,
      depart: LocalDate,
      ret: LocalDate
  ): IO[Option[FlightQuote]] = {
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
        case Left(_) => IO.pure(None)
        case Right(b) =>
          parser.parse(b) match {
            case Left(_) => IO.pure(None)
            case Right(json) =>
              val minPrice = extractMinPrice(json)
              IO.pure(
                minPrice.map(p =>
                  FlightQuote(
                    origin = origin,
                    destination = dest,
                    departureDate = depart,
                    returnDate = ret,
                    priceUsd = p
                  )
                )
              )
          }
      }
    }
  }
}
