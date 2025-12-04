package models

import java.time.{Instant, LocalDate}

final case class WeekendKey(
    airport: MetroAirport,
    weekendStart: LocalDate
)

final case class WeekendBucket(
    key: WeekendKey,
    tournaments: List[Tournament]
)

final case class FlightQuote(
    origin: String,
    destination: String,
    departureDate: LocalDate,
    returnDate: LocalDate,
    priceUsd: BigDecimal,
    outboundDepartureTime: String,
    outboundArrivalTime: String,
    airline: String,
    googleFlightsUrl: Option[String]
)

final case class QuoteCacheInfo(
    fromCache: Boolean,
    cacheAgeSeconds: Option[Long],
    cachedAt: Option[Instant]
)

final case class WeekendQuote(
    bucket: WeekendBucket,
    quotes: List[FlightQuote],
    cacheInfo: QuoteCacheInfo
) {
  def cheapestQuote: Option[FlightQuote] = quotes.sortBy(_.priceUsd).headOption
}
