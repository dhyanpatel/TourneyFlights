package api

import cats.effect.{IO, IOApp, Ref}
import com.comcast.ip4s._
import config.AppConfig
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.middleware.{CORS, Logger}
import sttp.client3.httpclient.cats.HttpClientCatsBackend

object ApiMain extends IOApp.Simple {

  def run: IO[Unit] =
    HttpClientCatsBackend.resource[IO]().use { backend =>
      val config = AppConfig.load()

      for {
        // Create thread-safe session store
        sessions <- Ref.of[IO, Map[String, ApiRoutes.SessionData]](Map.empty)

        // Build routes with CORS enabled for frontend
        httpApp = CORS.policy
          .withAllowOriginAll
          .httpRoutes(ApiRoutes.routes(backend, config, sessions))
          .orNotFound

        // Add request/response logging
        loggedApp = Logger.httpApp(logHeaders = false, logBody = false)(httpApp)

        _ <- IO.println("Starting TourneyFlights API server on http://localhost:8080")
        _ <- IO.println("")
        _ <- IO.println("Endpoints:")
        _ <- IO.println("  POST   /api/session    - Create session (requires apiKeys in request body)")
        _ <- IO.println("         Body: {\"apiKeys\": [\"key1\", \"key2\"]}")
        _ <- IO.println("         Returns: sessionId (use in X-Session-Id header)")
        _ <- IO.println("  DELETE /api/session    - End session (requires X-Session-Id header)")
        _ <- IO.println("")
        _ <- IO.println("  All endpoints below require X-Session-Id header:")
        _ <- IO.println("  GET  /api/summary      - Data summary statistics")
        _ <- IO.println("  GET  /api/airports     - List destination airports")
        _ <- IO.println("  GET  /api/states       - List states/regions")
        _ <- IO.println("  GET  /api/tournaments  - List tournament names")
        _ <- IO.println("  GET  /api/quotes       - Get quotes (with optional filters)")
        _ <- IO.println("       ?airport=LAX      - Filter by airport")
        _ <- IO.println("       ?state=CA         - Filter by state")
        _ <- IO.println("       ?maxPrice=200     - Filter by max price")
        _ <- IO.println("       ?search=open      - Search tournament names")
        _ <- IO.println("       ?friendsOnly=true - Only friend airports")
        _ <- IO.println("       ?limit=10         - Limit results")
        _ <- IO.println("       ?filtered=true    - Use pre-filtered quotes")
        _ <- IO.println("")

        _ <- EmberServerBuilder
          .default[IO]
          .withHost(host"0.0.0.0")
          .withPort(port"8080")
          .withHttpApp(loggedApp)
          .build
          .useForever
      } yield ()
    }
}
