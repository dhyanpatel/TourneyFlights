package report

import models._

object ReportFormatter {

  /** Pure: format a single WeekendQuote as a table row */
  def formatTableRow(wq: WeekendQuote): String = {
    val code   = wq.bucket.key.airport.code
    val wk     = wq.bucket.key.weekendStart.toString
    val friend = if (wq.isFriendAirport) "yes" else "no"

    val (priceStr, airline, outDep, outArr) = wq.quote match {
      case Some(q) =>
        (
          s"$$${q.priceUsd}",
          q.airline,
          q.outboundDepartureTime,
          q.outboundArrivalTime
        )
      case None =>
        ("no-price", "", "", "")
    }

    val ts = wq.bucket.tournaments
      .map(t => s"${t.name} (${t.city}, ${t.stateOrRegion}, ${t.rawDateText})")
      .mkString("; ")

    f"$code%-7s | $wk%-12s | $priceStr%-8s | $friend%-6s | $airline%-12s | $outDep%-16s | $outArr%-16s | $ts"
  }

  /** Pure: format a list of WeekendQuotes as a console table */
  def formatTable(list: List[WeekendQuote]): String = {
    val header =
      f"${"Airport"}%-7s | ${"Weekend"}%-12s | ${"Price"}%-8s | ${"Friend"}%-6s | ${"Airline"}%-12s | ${"Depart"}%-16s | ${"Arrive"}%-16s | Tournaments"

    val rows = list.map(formatTableRow)
    (header +: rows).mkString("\n")
  }

  /** Pure: format a single WeekendQuote as a markdown table row */
  def formatMarkdownRow(wq: WeekendQuote): String = {
    val code   = wq.bucket.key.airport.code
    val wk     = wq.bucket.key.weekendStart.toString
    val friend = if (wq.isFriendAirport) "yes" else "no"

    val (priceStr, airline, outDep, outArr) = wq.quote match {
      case Some(q) =>
        (
          s"$$${q.priceUsd}",
          Option(q.airline).getOrElse(""),
          Option(q.outboundDepartureTime).getOrElse(""),
          Option(q.outboundArrivalTime).getOrElse("")
        )
      case None =>
        ("no-price", "", "", "")
    }

    val ts = wq.bucket.tournaments
      .map(t => s"${t.name} (${t.city}, ${t.stateOrRegion}, ${t.rawDateText})")
      .mkString("<br>")

    s"| $code | $wk | $priceStr | $friend | $airline | $outDep | $outArr | $ts |"
  }

  /** Pure: format a list of WeekendQuotes as a markdown report */
  def formatMarkdown(title: String, generatedAt: String, list: List[WeekendQuote]): String = {
    val headerLines = List(
      s"# $title",
      "",
      s"_Generated at: $generatedAt",
      "",
      "| Airport | Weekend | Price | Friend | Airline | Depart | Arrive | Tournaments |",
      "|--------|---------|-------|--------|---------|--------|--------|------------|"
    )

    val rows = list.map(formatMarkdownRow)
    (headerLines ++ rows).mkString("\n")
  }

  /** Pure: format a summary of the current data state */
  def formatSummary(
      totalTournaments: Int,
      totalBuckets: Int,
      filteredBuckets: Int,
      quotesWithPrice: Int,
      quotesWithoutPrice: Int
  ): String = {
    s"""
       |=== Data Summary ===
       |Total tournaments scraped: $totalTournaments
       |Total weekend buckets: $totalBuckets
       |Buckets in date range: $filteredBuckets
       |Quotes with price: $quotesWithPrice
       |Quotes without price: $quotesWithoutPrice
       |""".stripMargin
  }

  /** Pure: format tournament details */
  def formatTournamentDetails(t: Tournament): String =
    s"  - ${t.name} | ${t.city}, ${t.stateOrRegion} | ${t.rawDateText}"

  /** Pure: format bucket details with tournaments */
  def formatBucketDetails(bucket: WeekendBucket): String = {
    val header = s"${bucket.key.airport.code} - Weekend of ${bucket.key.weekendStart}"
    val tournaments = bucket.tournaments.map(formatTournamentDetails).mkString("\n")
    s"$header\n$tournaments"
  }
}
