package config

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.time.LocalTime
import scala.jdk.CollectionConverters._
import scala.util.Try

final case class DepartureWindow(
    earliest: Option[LocalTime],
    latest: Option[LocalTime]
)

final case class AppConfig(
    apiKeys: List[String],
    originAirport: String,
    friendAirports: Set[String],
    maxApiCallsPerRun: Int,
    filterMonths: Long,
    maxPriceBase: Int,
    maxPriceFriend: Int,
    outboundWindow: DepartureWindow,
    returnWindow: DepartureWindow,
    cacheDir: Path
)

object AppConfig {

  private def readEnvFile(path: Path): Map[String, String] =
    if (!Files.exists(path)) Map.empty
    else {
      Files
        .readAllLines(path, StandardCharsets.UTF_8)
        .asScala
        .toList
        .map(_.trim)
        .filter(l => l.nonEmpty && !l.startsWith("#"))
        .flatMap { line =>
          val idx = line.indexOf('=')
          if (idx > 0) {
            val key   = line.substring(0, idx).trim
            val value = line.substring(idx + 1).trim
            if (key.nonEmpty) Some(key -> value) else None
          } else None
        }
        .toMap
    }

  private def parseTimeConfig(value: String): Option[LocalTime] =
    if (value == null || value.trim.isEmpty) None
    else Try(LocalTime.parse(value.trim)).toOption

  def load(): AppConfig = {
    val fileEnv           = readEnvFile(Paths.get(".env"))
    val env: Map[String, String] = sys.env ++ fileEnv

    def get(key: String): Option[String] =
      env.get(key).map(_.trim).filter(_.nonEmpty)

    val apiKeys = get("SERPAPI_KEYS")
      .map(_.split(",").toList.map(_.trim).filter(_.nonEmpty))
      .getOrElse(Nil)

    val originAirport = get("ORIGIN_AIRPORT").getOrElse("ORD")

    val friendAirports = get("FRIEND_AIRPORTS")
      .map(_.split(",").toList.map(_.trim).filter(_.nonEmpty).toSet)
      .getOrElse(Set("BOS", "AUS", "LAX"))

    val maxApiCallsPerRun = get("MAX_API_CALLS_PER_RUN")
      .flatMap(v => Try(v.toInt).toOption)
      .getOrElse(150)

    val filterMonths = get("FILTER_MONTHS")
      .flatMap(v => Try(v.toLong).toOption)
      .getOrElse(3L)

    val maxPriceBase = get("MAX_PRICE_BASE")
      .flatMap(v => Try(v.toInt).toOption)
      .getOrElse(150)

    val maxPriceFriend = get("MAX_PRICE_FRIEND")
      .flatMap(v => Try(v.toInt).toOption)
      .getOrElse(250)

    val outboundWindow = DepartureWindow(
      earliest = get("OUTBOUND_EARLIEST").flatMap(parseTimeConfig),
      latest   = get("OUTBOUND_LATEST").flatMap(parseTimeConfig)
    )

    val returnWindow = DepartureWindow(
      earliest = get("RETURN_EARLIEST").flatMap(parseTimeConfig),
      latest   = get("RETURN_LATEST").flatMap(parseTimeConfig)
    )

    val cacheDir = Paths.get("flight_cache")

    AppConfig(
      apiKeys          = apiKeys,
      originAirport    = originAirport,
      friendAirports   = friendAirports,
      maxApiCallsPerRun = maxApiCallsPerRun,
      filterMonths     = filterMonths,
      maxPriceBase     = maxPriceBase,
      maxPriceFriend   = maxPriceFriend,
      outboundWindow   = outboundWindow,
      returnWindow     = returnWindow,
      cacheDir         = cacheDir
    )
  }
}
