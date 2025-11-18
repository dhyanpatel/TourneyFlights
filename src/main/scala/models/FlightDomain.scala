package models

import java.time.LocalDate

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
    returnDepartureTime: String,
    returnArrivalTime: String
)

final case class WeekendQuote(
    bucket: WeekendBucket,
    quote: Option[FlightQuote],
    isFriendAirport: Boolean
)
