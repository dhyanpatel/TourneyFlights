package flights

import cats.effect.{IO, Ref}

/** Account information from SerpAPI */
final case class AccountInfo(
    accountEmail: String,
    planName: String,
    searchesPerMonth: Int,
    planSearchesLeft: Int,
    extraCredits: Int,
    totalSearchesLeft: Int,
    thisMonthUsage: Int,
    lastHourSearches: Int,
    rateLimitPerHour: Int
)

/** Manages multiple API keys with automatic rotation on 429 responses */
final class ApiKeyManager private (
    keys: Vector[String],
    currentIndexRef: Ref[IO, Int]
) {

  val keyCount: Int = keys.size

  /** Get the current API key */
  def currentKey: IO[String] =
    currentIndexRef.get.map(keys(_))

  /** Get the current key index (0-based) */
  def currentIndex: IO[Int] =
    currentIndexRef.get

  /** Rotate to the next API key, returns true if rotation happened, false if we've exhausted all keys */
  def rotateToNext: IO[Boolean] =
    currentIndexRef.modify { idx =>
      val nextIdx = idx + 1
      if (nextIdx >= keys.size) (idx, false)
      else (nextIdx, true)
    }

  /** Reset to the first API key */
  def reset: IO[Unit] =
    currentIndexRef.set(0)

  /** Get all API keys */
  def allKeys: Vector[String] = keys

  /** Get a masked version of a key for display */
  def maskKey(key: String): String =
    if (key.length <= 8) "****"
    else s"${key.take(4)}...${key.takeRight(4)}"
}

object ApiKeyManager {

  /** Create a new ApiKeyManager from a list of keys */
  def create(keys: List[String]): IO[ApiKeyManager] =
    for {
      ref <- Ref.of[IO, Int](0)
    } yield new ApiKeyManager(keys.toVector, ref)

  /** Create from a single key (backward compatible) */
  def single(key: String): IO[ApiKeyManager] =
    create(List(key))
}
