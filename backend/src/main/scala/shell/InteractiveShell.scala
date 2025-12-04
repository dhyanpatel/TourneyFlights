package shell

import cats.effect.IO
import cats.effect.std.Console

/** Interactive REPL shell for exploring flight data */
object InteractiveShell {

  private val prompt = "tourney> "

  /** Run the interactive shell loop */
  def run(initialState: AppState, reloadAction: IO[AppState]): IO[Unit] = {
    val console = Console[IO]

    def printWelcome: IO[Unit] =
      console.println(welcomeMessage(initialState))

    def loop(state: AppState): IO[Unit] =
      for {
        _        <- console.print(prompt)
        input    <- console.readLine
        cmd       = ShellCommand.parse(input)
        result   <- handleCommand(cmd, state, reloadAction)
        (newState, continue) = result
        _        <- if (continue) loop(newState) else IO.unit
      } yield ()

    printWelcome *> loop(initialState)
  }

  private def handleCommand(
      cmd: ShellCommand,
      state: AppState,
      reloadAction: IO[AppState]
  ): IO[(AppState, Boolean)] = {
    val console = Console[IO]

    cmd match {
      case ShellCommand.Reload =>
        for {
          _        <- console.println("Reloading data...")
          newState <- reloadAction
          _        <- console.println(s"Reloaded: ${newState.summary.totalTournaments} tournaments, ${newState.summary.totalQuotes} quotes")
        } yield (newState, true)

      case other =>
        CommandExecutor.execute(other, state).flatMap { case (output, continue) =>
          console.println(output).as((state, continue))
        }
    }
  }

  private def welcomeMessage(state: AppState): String = {
    val s = state.summary
    s"""
       |╔══════════════════════════════════════════════════════════════╗
       |║           TourneyFlights Interactive Shell                   ║
       |╚══════════════════════════════════════════════════════════════╝
       |
       |Data loaded:
       |  • ${s.totalTournaments} tournaments
       |  • ${s.totalBuckets} weekend buckets
       |  • ${s.totalQuotes} flight quotes fetched
       |  • ${s.filteredQuotesCount} quotes match your filters
       |
       |Type 'help' for available commands, 'quit' to exit.
       |""".stripMargin
  }
}
