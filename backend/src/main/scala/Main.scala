import api.ApiRoutes
import cats.effect.{IO, IOApp, Ref}
import com.comcast.ip4s._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.middleware.{CORS, Logger}
import sttp.client3.httpclient.cats.HttpClientCatsBackend
import scala.concurrent.duration._

object Main extends IOApp.Simple {

  def run: IO[Unit] =
    HttpClientCatsBackend.resource[IO]().use { backend =>
      for {
        // Create thread-safe session store
        sessions <- Ref.of[IO, Map[String, ApiRoutes.SessionData]](Map.empty)

        // Build routes with CORS enabled for frontend
        httpApp = CORS.policy
          .withAllowOriginAll
          .httpRoutes(ApiRoutes.routes(backend, sessions))
          .orNotFound

        // Add request/response logging
        loggedApp = Logger.httpApp(logHeaders = false, logBody = false)(httpApp)

        _ <- IO.println("Starting TourneyFlights API server on http://localhost:8080")
        _ <- IO.println("")
        _ <- IO.println("Session Management:")
        _ <- IO.println("  POST   /api/session         - Create session")
        _ <- IO.println("         Body: {\"apiKeys\": [...], \"config\": {...}}")
        _ <- IO.println("  GET    /api/session         - View current session info")
        _ <- IO.println("  DELETE /api/session         - End session")
        _ <- IO.println("  PATCH  /api/session/config  - Update session config")
        _ <- IO.println("")
        _ <- IO.println("Flight Search:")
        _ <- IO.println("  POST   /api/flights/search  - Search for flights")
        _ <- IO.println("         Body: {originAirport, destinationAirport, departureDate, returnDate, maxResults, skipCache}")
        _ <- IO.println("  GET    /api/flights/quotes  - Get cached quotes with filters")
        _ <- IO.println("")
        _ <- IO.println("Data Endpoints:")
        _ <- IO.println("  GET    /api/health          - Health check (no auth)")
        _ <- IO.println("  GET    /api/airports        - List destination airports")
        _ <- IO.println("  GET    /api/states          - List states/regions")
        _ <- IO.println("  GET    /api/tournaments     - List tournament names")
        _ <- IO.println("  GET    /api/buckets         - List weekend buckets")
        _ <- IO.println("  GET    /api/keys/usage      - Get API key usage stats")
        _ <- IO.println("")
        _ <- IO.println("Press Ctrl+C to shutdown gracefully")
        _ <- IO.println("")

        _ <- EmberServerBuilder
          .default[IO]
          .withHost(host"0.0.0.0")
          .withPort(port"8080")
          .withHttpApp(loggedApp)
          .withShutdownTimeout(10.seconds)
          .build
          .use { server =>
            IO.println(s"Server started on ${server.address}") *>
              IO.never
          }
      } yield ()
    }
}
