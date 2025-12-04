package flights

import cats.effect.IO
import cats.syntax.traverse._
import io.circe.parser
import sttp.client3._

/** Client for checking SerpAPI account status */
object AccountClient {

  private val accountUri = uri"https://serpapi.com/account.json"

  /** Fetch account info for a single API key */
  def fetchAccountInfo(
      backend: SttpBackend[IO, Any],
      apiKey: String
  ): IO[Either[String, AccountInfo]] = {
    val req = basicRequest.get(accountUri.addParam("api_key", apiKey))

    req.send(backend).map { resp =>
      resp.body match {
        case Left(err) => Left(s"API error: $err")
        case Right(body) =>
          parser.parse(body) match {
            case Left(pe) => Left(s"JSON parse error: $pe")
            case Right(json) =>
              val c = json.hcursor
              for {
                email           <- c.downField("account_email").as[String].left.map(_.message)
                planName        <- c.downField("plan_name").as[String].left.map(_.message)
                searchesPerMonth <- c.downField("searches_per_month").as[Int].left.map(_.message)
                planSearchesLeft <- c.downField("plan_searches_left").as[Int].left.map(_.message)
                extraCredits    <- c.downField("extra_credits").as[Int].left.map(_.message)
                totalLeft       <- c.downField("total_searches_left").as[Int].left.map(_.message)
                thisMonth       <- c.downField("this_month_usage").as[Int].left.map(_.message)
                lastHour        <- c.downField("last_hour_searches").as[Int].left.map(_.message)
                rateLimit       <- c.downField("account_rate_limit_per_hour").as[Int].left.map(_.message)
              } yield AccountInfo(
                accountEmail      = email,
                planName          = planName,
                searchesPerMonth  = searchesPerMonth,
                planSearchesLeft  = planSearchesLeft,
                extraCredits      = extraCredits,
                totalSearchesLeft = totalLeft,
                thisMonthUsage    = thisMonth,
                lastHourSearches  = lastHour,
                rateLimitPerHour  = rateLimit
              )
          }
      }
    }
  }

  /** Fetch account info for all API keys */
  def fetchAllAccountInfo(
      backend: SttpBackend[IO, Any],
      keyManager: ApiKeyManager
  ): IO[List[(String, Either[String, AccountInfo])]] = {
    keyManager.allKeys.toList.traverse { key =>
      fetchAccountInfo(backend, key).map(result => (keyManager.maskKey(key), result))
    }
  }

  /** Format account info for display */
  def formatAccountInfo(info: AccountInfo): String =
    s"""  Email: ${info.accountEmail}
       |  Plan: ${info.planName}
       |  Monthly limit: ${info.searchesPerMonth}
       |  Searches left: ${info.planSearchesLeft}
       |  Extra credits: ${info.extraCredits}
       |  Total left: ${info.totalSearchesLeft}
       |  This month usage: ${info.thisMonthUsage}
       |  Last hour: ${info.lastHourSearches}
       |  Rate limit/hour: ${info.rateLimitPerHour}""".stripMargin

  /** Format all account info for display */
  def formatAllAccountInfo(results: List[(String, Either[String, AccountInfo])]): String = {
    val header = s"=== API Key Status (${results.size} keys) ===\n"
    val entries = results.zipWithIndex.map { case ((maskedKey, result), idx) =>
      val keyHeader = s"\n[${idx + 1}] Key: $maskedKey"
      result match {
        case Right(info) => s"$keyHeader\n${formatAccountInfo(info)}"
        case Left(err)   => s"$keyHeader\n  Error: $err"
      }
    }
    header + entries.mkString("\n")
  }
}
