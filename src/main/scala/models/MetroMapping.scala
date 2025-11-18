package models

final case class MetroAirport(code: String) extends AnyVal

object MetroMapping {

  private val stateNorm: Map[String, String] = Map(
    "CA" -> "CA",
    "California" -> "CA",
    "NV" -> "NV",
    "Nevada" -> "NV",
    "AZ" -> "AZ",
    "Arizona" -> "AZ",
    "TX" -> "TX",
    "Texas" -> "TX",
    "IL" -> "IL",
    "Illinois" -> "IL",
    "FL" -> "FL",
    "Florida" -> "FL",
    "GA" -> "GA",
    "Georgia" -> "GA",
    "NC" -> "NC",
    "North Carolina" -> "NC",
    "SC" -> "SC",
    "South Carolina" -> "SC",
    "OK" -> "OK",
    "Oklahoma" -> "OK",
    "MI" -> "MI",
    "Michigan" -> "MI",
    "RI" -> "RI",
    "Rhode Island" -> "RI",
    "PA" -> "PA",
    "Pennsylvania" -> "PA",
    "MD" -> "MD",
    "Maryland" -> "MD",
    "NJ" -> "NJ",
    "New Jersey" -> "NJ",
    "NY" -> "NY",
    "New York" -> "NY",
    "WA" -> "WA",
    "Washington" -> "WA",
    "OR" -> "OR",
    "Oregon" -> "OR",
    "KS" -> "KS",
    "Kansas" -> "KS",
    "AL" -> "AL",
    "Alabama" -> "AL",
    "IN" -> "IN",
    "Indiana" -> "IN",
    "OH" -> "OH",
    "Ohio" -> "OH",
    "MA" -> "MA",
    "Massachusetts" -> "MA",
    "WI" -> "WI",
    "Wisconsin" -> "WI",
    "BR" -> "BR",
    "Brazil" -> "BR"
  )

  // (city.toLowerCase, stateCode) -> metro airport
  private val table: Map[(String, String), MetroAirport] = Map(
    (("homewood", "AL"), MetroAirport("BHM")), // Birmingham
    (("gilbert", "AZ"), MetroAirport("PHX")),
    (("phoenix", "AZ"), MetroAirport("PHX")),
    (("tres coroas", "BR"), MetroAirport("POA")), // Porto Alegre

    // California / Bay Area / SoCal
    (("alameda", "CA"), MetroAirport("SFO")),
    (("anaheim", "CA"), MetroAirport("LAX")),
    (("burlingame", "CA"), MetroAirport("SFO")),
    (("el monte", "CA"), MetroAirport("LAX")),
    (("fountain valley", "CA"), MetroAirport("LAX")),
    (("fremont", "CA"), MetroAirport("SFO")),
    (("fullerton", "CA"), MetroAirport("LAX")),
    (("milpitas", "CA"), MetroAirport("SFO")),
    (("newark", "CA"), MetroAirport("SFO")),
    (("sacramento", "CA"), MetroAirport("SMF")),
    (("san diego", "CA"), MetroAirport("SAN")),
    (("south el monte", "CA"), MetroAirport("LAX")),

    // Florida
    (("davie", "FL"), MetroAirport("FLL")),
    (("doral", "FL"), MetroAirport("MIA")),
    (("jacksonville", "FL"), MetroAirport("JAX")),
    (("naples", "FL"), MetroAirport("RSW")), // Fort Myers
    (("ocala", "FL"), MetroAirport("OCF")),
    (("orlando", "FL"), MetroAirport("MCO")),
    (("pensacola", "FL"), MetroAirport("PNS")),

    // Georgia
    (("athens", "GA"), MetroAirport("ATL")),
    (("norcross", "GA"), MetroAirport("ATL")),
    (("suwanee", "GA"), MetroAirport("ATL")),

    // Illinois / Chicago area
    (("barrington", "IL"), MetroAirport("ORD")),
    (("rolling meadows", "IL"), MetroAirport("ORD")),

    // Indiana
    (("south bend", "IN"), MetroAirport("SBN")),

    // Kansas City area
    (("leawood", "KS"), MetroAirport("MCI")),

    // Massachusetts / Boston
    (("dedham", "MA"), MetroAirport("BOS")),
    (("westford", "MA"), MetroAirport("BOS")),

    // Maryland
    (("jessup", "MD"), MetroAirport("BWI")),

    // Michigan / Detroit area
    (("brighton", "MI"), MetroAirport("DTW")),
    (("clinton township", "MI"), MetroAirport("DTW")),

    // North Carolina
    (("asheville", "NC"), MetroAirport("AVL")),
    (("charlotte", "NC"), MetroAirport("CLT")),
    (("fayetteville", "NC"), MetroAirport("FAY")),

    // New Jersey / NYC area
    (("dunellen", "NJ"), MetroAirport("EWR")),
    (("princeton", "NJ"), MetroAirport("EWR")),

    // Nevada
    (("las vegas", "NV"), MetroAirport("LAS")),

    // New York
    (("rochester", "NY"), MetroAirport("ROC")),

    // Ohio
    (("columbus", "OH"), MetroAirport("CMH")),
    (("plain city", "OH"), MetroAirport("CMH")),

    // Oklahoma
    (("bixby", "OK"), MetroAirport("TUL")),

    // Oregon / Portland
    (("tigard", "OR"), MetroAirport("PDX")),

    // Pennsylvania
    (("erie", "PA"), MetroAirport("ERI")),
    (("millersville", "PA"), MetroAirport("MDT")), // Harrisburg-ish
    (("phoenixville", "PA"), MetroAirport("PHL")),
    (("shippensburg", "PA"), MetroAirport("MDT")),

    // Rhode Island
    (("lincoln", "RI"), MetroAirport("PVD")),

    // South Carolina
    (("columbia", "SC"), MetroAirport("CAE")),

    // Texas / DFW / Austin / San Antonio / Houston
    (("allen", "TX"), MetroAirport("DFW")),
    (("austin", "TX"), MetroAirport("AUS")),
    (("colleyville", "TX"), MetroAirport("DFW")),
    (("irving", "TX"), MetroAirport("DFW")),
    (("katy", "TX"), MetroAirport("IAH")), // Houston area
    (("plano", "TX"), MetroAirport("DFW")),
    (("richardson", "TX"), MetroAirport("DFW")),
    (("san antonio", "TX"), MetroAirport("SAT")),

    // Washington / Seattle area
    (("bellevue", "WA"), MetroAirport("SEA")),
    (("redmond", "WA"), MetroAirport("SEA")),

    // Wisconsin / Milwaukee
    (("shorewood", "WI"), MetroAirport("MKE"))
  )

  def airportFor(rawCity: String, rawState: String): Option[MetroAirport] = {
    val normState = stateNorm.getOrElse(rawState, rawState).toUpperCase
    val key = (rawCity.toLowerCase, normState)
    table.get(key)
  }
}
