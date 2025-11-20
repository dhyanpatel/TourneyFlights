ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "3.3.7"

lazy val root = (project in file("."))
  .settings(
    name := "TourneyFlights",
    scalafmtOnCompile := sys.env.get("SCALAFMT_ON_COMPILE").fold(false)(_.toBoolean)
  )

libraryDependencies ++= Seq(
  // Cats Effect
  "org.typelevel" %% "cats-effect" % "3.6.3",

  // STTP HTTP client (Cats Effect backend)
  "com.softwaremill.sttp.client3" %% "core" % "3.11.0",
  "com.softwaremill.sttp.client3" %% "cats" % "3.11.0",

  // Circe for JSON (flight API parsing later)
  "io.circe" %% "circe-core" % "0.14.15",
  "io.circe" %% "circe-generic" % "0.14.15",
  "io.circe" %% "circe-parser" % "0.14.15",

  // Jsoup for scraping
  "org.jsoup" % "jsoup" % "1.21.2",

  // Scala Scraper (cleaner JSoup wrapper)
  "net.ruippeixotog" %% "scala-scraper" % "3.2.0"
)

Compile / mainClass := Some("Main")
assembly / assemblyJarName := "tourneyFlights.jar"

import sbtassembly.MergeStrategy

assembly / assemblyMergeStrategy := {
  case path if path.endsWith("module-info.class") =>
    MergeStrategy.discard

  case other =>
    (assembly / assemblyMergeStrategy).value(other)
}
