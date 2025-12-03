package shell

/** ADT representing all shell commands */
sealed trait ShellCommand

object ShellCommand {

  // Display commands
  case object Help                                    extends ShellCommand
  case object Summary                                 extends ShellCommand
  case object ListAirports                            extends ShellCommand
  case object ListStates                              extends ShellCommand
  case object ListTournaments                         extends ShellCommand

  // Query commands - show filtered results
  case object ShowAll                                 extends ShellCommand
  case object ShowFiltered                            extends ShellCommand
  case class  ShowByAirport(code: String)             extends ShellCommand
  case class  ShowByState(state: String)              extends ShellCommand
  case class  ShowByMaxPrice(price: Int)              extends ShellCommand
  case class  ShowByTournament(nameSubstring: String) extends ShellCommand
  case object ShowFriendAirports                      extends ShellCommand
  case class  ShowTop(n: Int)                         extends ShellCommand

  // Export commands
  case class  ExportMarkdown(fileName: Option[String]) extends ShellCommand
  case class  ExportFiltered(fileName: Option[String]) extends ShellCommand
  case class  ExportByAirport(code: String, fileName: Option[String]) extends ShellCommand
  case class  ExportByMaxPrice(price: Int, fileName: Option[String])  extends ShellCommand

  // API commands
  case object AccountStatus                           extends ShellCommand

  // Control commands
  case object Reload                                  extends ShellCommand
  case object Quit                                    extends ShellCommand

  // Error case
  case class  Unknown(input: String)                  extends ShellCommand

  /** Pure: parse a command string into a ShellCommand */
  def parse(input: String): ShellCommand = {
    val trimmed = input.trim
    val parts   = trimmed.split("\\s+").toList

    parts match {
      case Nil | "" :: Nil                  => Help
      case "help" :: _                      => Help
      case "h" :: _                         => Help
      case "?" :: _                         => Help

      case "summary" :: _                   => Summary
      case "stats" :: _                     => Summary

      case "airports" :: _                  => ListAirports
      case "states" :: _                    => ListStates
      case "tournaments" :: _               => ListTournaments

      case "all" :: _                       => ShowAll
      case "show" :: "all" :: _             => ShowAll
      case "filtered" :: _                  => ShowFiltered
      case "show" :: "filtered" :: _        => ShowFiltered

      case "airport" :: code :: _           => ShowByAirport(code.toUpperCase)
      case "show" :: "airport" :: code :: _ => ShowByAirport(code.toUpperCase)

      case "state" :: rest                  => ShowByState(rest.mkString(" "))
      case "show" :: "state" :: rest        => ShowByState(rest.mkString(" "))

      case "price" :: p :: _                => parsePrice(p).map(ShowByMaxPrice.apply).getOrElse(Unknown(trimmed))
      case "max" :: p :: _                  => parsePrice(p).map(ShowByMaxPrice.apply).getOrElse(Unknown(trimmed))
      case "show" :: "price" :: p :: _      => parsePrice(p).map(ShowByMaxPrice.apply).getOrElse(Unknown(trimmed))

      case "search" :: rest                 => ShowByTournament(rest.mkString(" "))
      case "find" :: rest                   => ShowByTournament(rest.mkString(" "))

      case "friends" :: _                   => ShowFriendAirports
      case "friend" :: _                    => ShowFriendAirports

      case "top" :: n :: _                  => parseIntSafe(n).map(ShowTop.apply).getOrElse(Unknown(trimmed))

      case "export" :: rest                 => parseExport(rest)
      case "md" :: rest                     => parseExport(rest)
      case "markdown" :: rest               => parseExport(rest)

      case "account" :: _                   => AccountStatus
      case "accounts" :: _                  => AccountStatus
      case "credits" :: _                   => AccountStatus
      case "keys" :: _                      => AccountStatus
      case "status" :: _                    => AccountStatus

      case "reload" :: _                    => Reload
      case "refresh" :: _                   => Reload

      case "quit" :: _                      => Quit
      case "exit" :: _                      => Quit
      case "q" :: _                         => Quit

      case _                                => Unknown(trimmed)
    }
  }

  private def parseIntSafe(s: String): Option[Int] =
    try Some(s.toInt)
    catch { case _: NumberFormatException => None }

  private def parsePrice(s: String): Option[Int] = {
    val cleaned = s.replaceAll("[$,]", "")
    parseIntSafe(cleaned)
  }

  private def parseExport(args: List[String]): ShellCommand = args match {
    case Nil                              => ExportMarkdown(None)
    case "all" :: Nil                     => ExportMarkdown(None)
    case "all" :: name :: _               => ExportMarkdown(Some(ensureMdExtension(name)))
    case "filtered" :: Nil                => ExportFiltered(None)
    case "filtered" :: name :: _          => ExportFiltered(Some(ensureMdExtension(name)))
    case "airport" :: code :: Nil         => ExportByAirport(code.toUpperCase, None)
    case "airport" :: code :: name :: _   => ExportByAirport(code.toUpperCase, Some(ensureMdExtension(name)))
    case "price" :: p :: rest             =>
      parsePrice(p) match {
        case Some(price) => ExportByMaxPrice(price, rest.headOption.map(ensureMdExtension))
        case None        => Unknown(s"export price $p ${rest.mkString(" ")}")
      }
    case name :: _                        => ExportMarkdown(Some(ensureMdExtension(name)))
  }

  private def ensureMdExtension(name: String): String =
    if (name.endsWith(".md")) name else s"$name.md"
}
