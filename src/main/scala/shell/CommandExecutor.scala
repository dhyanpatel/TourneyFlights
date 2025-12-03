package shell

import cats.effect.IO
import flights.AccountClient
import report.{ReportFormatter, ReportWriter}
import services.FlightFilterService

/** Executes shell commands against the application state */
object CommandExecutor {

  /** Execute a command and return the output string and whether to continue the REPL */
  def execute(cmd: ShellCommand, state: AppState): IO[(String, Boolean)] = cmd match {

    case ShellCommand.Help =>
      IO.pure((helpText, true))

    case ShellCommand.Summary =>
      val s = state.summary
      val output = s"""
        |=== Data Summary ===
        |Total tournaments: ${s.totalTournaments}
        |Total weekend buckets: ${s.totalBuckets}
        |Buckets in date range: ${s.filteredBuckets}
        |Total quotes fetched: ${s.totalQuotes}
        |  - With price: ${s.quotesWithPrice}
        |  - Without price: ${s.quotesWithoutPrice}
        |Filtered quotes (ideal): ${s.filteredQuotesCount}
        |Origin airport: ${state.config.originAirport}
        |Friend airports: ${state.config.friendAirports.mkString(", ")}
        |""".stripMargin
      IO.pure((output, true))

    case ShellCommand.ListAirports =>
      val airports = state.uniqueAirports
      val output   = s"Airports (${airports.size}):\n${airports.mkString(", ")}"
      IO.pure((output, true))

    case ShellCommand.ListStates =>
      val states = state.uniqueStates
      val output = s"States/Regions (${states.size}):\n${states.mkString(", ")}"
      IO.pure((output, true))

    case ShellCommand.ListTournaments =>
      val names  = state.tournamentNames
      val output = s"Tournaments (${names.size}):\n${names.mkString("\n")}"
      IO.pure((output, true))

    case ShellCommand.ShowAll =>
      val table = ReportFormatter.formatTable(state.weekendQuotes)
      IO.pure((s"All quotes (${state.weekendQuotes.size}):\n$table", true))

    case ShellCommand.ShowFiltered =>
      val table = ReportFormatter.formatTable(state.filteredQuotes)
      IO.pure((s"Filtered quotes (${state.filteredQuotes.size}):\n$table", true))

    case ShellCommand.ShowByAirport(code) =>
      val filtered = FlightFilterService.filterByAirport(state.weekendQuotes, code)
      val table    = ReportFormatter.formatTable(filtered)
      IO.pure((s"Quotes for $code (${filtered.size}):\n$table", true))

    case ShellCommand.ShowByState(st) =>
      val filtered = FlightFilterService.filterByState(state.weekendQuotes, st)
      val table    = ReportFormatter.formatTable(filtered)
      IO.pure((s"Quotes for state '$st' (${filtered.size}):\n$table", true))

    case ShellCommand.ShowByMaxPrice(price) =>
      val filtered = FlightFilterService.filterByMaxPrice(state.weekendQuotes, price)
      val sorted   = FlightFilterService.sortByPrice(filtered)
      val table    = ReportFormatter.formatTable(sorted)
      IO.pure((s"Quotes under $$$price (${filtered.size}):\n$table", true))

    case ShellCommand.ShowByTournament(name) =>
      val filtered = FlightFilterService.filterByTournamentName(state.weekendQuotes, name)
      val table    = ReportFormatter.formatTable(filtered)
      IO.pure((s"Quotes matching '$name' (${filtered.size}):\n$table", true))

    case ShellCommand.ShowFriendAirports =>
      val filtered = FlightFilterService.filterFriendAirportsOnly(state.weekendQuotes)
      val sorted   = FlightFilterService.sortByPrice(filtered)
      val table    = ReportFormatter.formatTable(sorted)
      IO.pure((s"Friend airport quotes (${filtered.size}):\n$table", true))

    case ShellCommand.ShowTop(n) =>
      val sorted = FlightFilterService.sortByPrice(state.weekendQuotes).take(n)
      val table  = ReportFormatter.formatTable(sorted)
      IO.pure((s"Top $n cheapest quotes:\n$table", true))

    case ShellCommand.ExportMarkdown(fileNameOpt) =>
      val fileName = fileNameOpt.getOrElse("all_flights")
      ReportWriter.writeMarkdownReport(fileName.stripSuffix(".md"), "All Flight Quotes", state.weekendQuotes)
        .map(path => (s"Exported to: $path", true))

    case ShellCommand.ExportFiltered(fileNameOpt) =>
      val fileName = fileNameOpt.getOrElse("filtered_flights")
      ReportWriter.writeMarkdownReport(fileName.stripSuffix(".md"), "Filtered Flight Quotes", state.filteredQuotes)
        .map(path => (s"Exported to: $path", true))

    case ShellCommand.ExportByAirport(code, fileNameOpt) =>
      val filtered = FlightFilterService.filterByAirport(state.weekendQuotes, code)
      val fileName = fileNameOpt.getOrElse(s"flights_$code")
      ReportWriter.writeMarkdownReport(fileName.stripSuffix(".md"), s"Flights to $code", filtered)
        .map(path => (s"Exported ${filtered.size} quotes to: $path", true))

    case ShellCommand.ExportByMaxPrice(price, fileNameOpt) =>
      val filtered = FlightFilterService.filterByMaxPrice(state.weekendQuotes, price)
      val sorted   = FlightFilterService.sortByPrice(filtered)
      val fileName = fileNameOpt.getOrElse(s"flights_under_$price")
      ReportWriter.writeMarkdownReport(fileName.stripSuffix(".md"), s"Flights Under $$$price", sorted)
        .map(path => (s"Exported ${sorted.size} quotes to: $path", true))

    case ShellCommand.AccountStatus =>
      AccountClient.fetchAllAccountInfo(state.backend, state.keyManager).map { results =>
        (AccountClient.formatAllAccountInfo(results), true)
      }

    case ShellCommand.Reload =>
      IO.pure(("Use 'reload' from main menu to refresh data.", true))

    case ShellCommand.Quit =>
      IO.pure(("Goodbye!", false))

    case ShellCommand.Unknown(input) =>
      IO.pure((s"Unknown command: '$input'. Type 'help' for available commands.", true))
  }

  private val helpText: String =
    """
      |=== TourneyFlights Interactive Shell ===
      |
      |DISPLAY COMMANDS:
      |  help, h, ?           Show this help message
      |  summary, stats       Show data summary statistics
      |  airports             List all destination airports
      |  states               List all states/regions
      |  tournaments          List all tournament names
      |
      |QUERY COMMANDS:
      |  all, show all        Show all fetched quotes
      |  filtered             Show filtered (ideal) quotes
      |  airport <CODE>       Show quotes for specific airport (e.g., airport LAX)
      |  state <NAME>         Show quotes for state (e.g., state CA)
      |  price <AMOUNT>       Show quotes under price (e.g., price 200)
      |  max <AMOUNT>         Same as price
      |  search <TEXT>        Search tournaments by name
      |  find <TEXT>          Same as search
      |  friends              Show only friend airport quotes
      |  top <N>              Show top N cheapest quotes
      |
      |EXPORT COMMANDS:
      |  export               Export all quotes to markdown
      |  export all [name]    Export all quotes (optional filename)
      |  export filtered [n]  Export filtered quotes
      |  export airport <C>   Export quotes for airport code
      |  export price <P>     Export quotes under price
      |  md [name]            Same as export
      |
      |API COMMANDS:
      |  account, credits     Check API key status and remaining credits
      |  keys, status         Same as account
      |
      |CONTROL COMMANDS:
      |  reload, refresh      Reload data from source
      |  quit, exit, q        Exit the shell
      |""".stripMargin
}
