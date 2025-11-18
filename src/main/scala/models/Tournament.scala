package models

import java.time.LocalDate

final case class Tournament(
    name: String,
    city: String,
    stateOrRegion: String,
    startDate: LocalDate,
    endDate: LocalDate,
    rawDateText: String
)
