# TourneyFlights

A Scala application that scrapes upcoming USATT table-tennis tournaments and checks flight prices (via SerpApi → Google Flights). Features an **interactive shell** for exploring data, filtering results, and exporting markdown reports.

---

## Features

- Scrapes tournament data from Omnipong
- Groups tournaments by weekend and destination airport
- Fetches flight prices via SerpApi (Google Flights)
- **Interactive shell** for querying and filtering results
- Supports **multiple API keys** with automatic rotation on rate limits (429)
- Caches flight data to minimize API calls
- Exports filtered results to markdown

---

## 1. Getting SerpApi API Keys

1. Go to [https://serpapi.com](https://serpapi.com)
2. Click **Sign Up** and create an account
3. After logging in, open your Dashboard
4. Copy your API key from the **API Key** section

SerpApi gives you 250 free searches/month. You can configure multiple API keys for higher throughput.

---

## 2. Create Your `.env` File

In the root directory, create a `.env` file. Use `.env.example` as a template:

```bash
# API keys (comma-separated for multiple keys with automatic rotation on 429)
SERPAPI_KEYS=key1,key2,key3
# Or use a single key:
# SERPAPI_KEY=your_single_key

# Your home airport
ORIGIN_AIRPORT=ORD

# Airports where you have friends (higher price threshold)
FRIEND_AIRPORTS=BOS,AUS,LAX

# Limits
MAX_API_CALLS_PER_RUN=150
FILTER_MONTHS=3

# Price thresholds (USD)
MAX_PRICE_BASE=150
MAX_PRICE_FRIEND=250

# Departure time windows (HH:mm format, leave blank for any time)
OUTBOUND_EARLIEST=18:00
OUTBOUND_LATEST=
RETURN_EARLIEST=
RETURN_LATEST=23:00
```

### Configuration Options

| Variable | Description | Default |
|----------|-------------|---------|
| `SERPAPI_KEYS` | Comma-separated API keys (rotates on 429) | - |
| `SERPAPI_KEY` | Single API key (fallback) | - |
| `ORIGIN_AIRPORT` | Your home airport code | `ORD` |
| `FRIEND_AIRPORTS` | Airports with higher price threshold | `BOS,AUS,LAX` |
| `MAX_API_CALLS_PER_RUN` | Max flight queries per run | `150` |
| `FILTER_MONTHS` | How many months ahead to search | `3` |
| `MAX_PRICE_BASE` | Max price for regular airports | `$150` |
| `MAX_PRICE_FRIEND` | Max price for friend airports | `$250` |
| `OUTBOUND_EARLIEST` | Earliest acceptable departure time | Any |
| `OUTBOUND_LATEST` | Latest acceptable departure time | Any |
| `RETURN_EARLIEST` | Earliest acceptable return time | Any |
| `RETURN_LATEST` | Latest acceptable return time | Any |

---

## 3. Running the Program

### From JAR

Download from GitHub Releases:
```
Releases → Assets → tourneyFlights.jar
```

Run with:
```bash
java -jar tourneyFlights.jar
```

### From Source

```bash
sbt run
```

---

## 4. Interactive Shell

After loading data, you'll enter an interactive shell:

```
╔══════════════════════════════════════════════════════════════╗
║           TourneyFlights Interactive Shell                   ║
╚══════════════════════════════════════════════════════════════╝

Data loaded:
  • 45 tournaments
  • 32 weekend buckets
  • 32 flight quotes fetched
  • 12 quotes match your filters

Type 'help' for available commands, 'quit' to exit.

tourney>
```

### Shell Commands

#### Display Commands
| Command | Description |
|---------|-------------|
| `help`, `h`, `?` | Show help message |
| `summary`, `stats` | Show data summary statistics |
| `airports` | List all destination airports |
| `states` | List all states/regions |
| `tournaments` | List all tournament names |

#### Query Commands
| Command | Description |
|---------|-------------|
| `all`, `show all` | Show all fetched quotes |
| `filtered` | Show filtered (ideal) quotes |
| `airport <CODE>` | Show quotes for specific airport (e.g., `airport LAX`) |
| `state <NAME>` | Show quotes for state (e.g., `state CA`) |
| `price <AMOUNT>` | Show quotes under price (e.g., `price 200`) |
| `max <AMOUNT>` | Same as `price` |
| `search <TEXT>` | Search tournaments by name |
| `find <TEXT>` | Same as `search` |
| `friends` | Show only friend airport quotes |
| `top <N>` | Show top N cheapest quotes |

#### Export Commands
| Command | Description |
|---------|-------------|
| `export` | Export all quotes to markdown |
| `export all [name]` | Export all quotes (optional filename) |
| `export filtered [name]` | Export filtered quotes |
| `export airport <CODE> [name]` | Export quotes for airport code |
| `export price <AMOUNT> [name]` | Export quotes under price |
| `md [name]` | Same as `export` |

#### API Commands
| Command | Description |
|---------|-------------|
| `account`, `credits` | Check API key status and remaining credits |
| `keys`, `status` | Same as `account` |

#### Control Commands
| Command | Description |
|---------|-------------|
| `reload`, `refresh` | Reload data from source |
| `quit`, `exit`, `q` | Exit the shell |

---

## 5. Examples

### Find cheap flights to California
```
tourney> state CA
Quotes for state 'CA' (5):
Airport | Weekend      | Price    | Friend | ...
LAX     | 2025-01-10   | $89      | yes    | ...
SFO     | 2025-01-17   | $120     | no     | ...
```

### Show top 5 cheapest options
```
tourney> top 5
Top 5 cheapest quotes:
Airport | Weekend      | Price    | ...
ATL     | 2025-01-03   | $65      | ...
DFW     | 2025-01-10   | $78      | ...
```

### Export filtered results
```
tourney> export filtered my_trips
Exported to: my_trips_20251202_143022.md
```

### Check API key credits
```
tourney> account
=== API Key Status (3 keys) ===

[1] Key: abc1...xyz9
  Email: user@example.com
  Plan: Free Plan
  Searches left: 42
  ...
```

---

## 6. Project Structure

```
src/main/scala/
├── Main.scala                 # Entry point, pipeline orchestration
├── config/
│   └── AppConfig.scala        # Configuration loading
├── flights/
│   ├── FlightsClient.scala    # SerpAPI flight queries
│   ├── ApiKeyManager.scala    # Multi-key rotation
│   └── AccountClient.scala    # API account status
├── models/
│   ├── Tournament.scala       # Tournament data model
│   ├── FlightDomain.scala     # Flight/quote models
│   └── MetroMapping.scala     # City → Airport mapping
├── report/
│   ├── ReportFormatter.scala  # Pure formatting functions
│   └── ReportWriter.scala     # Markdown file output
├── scraper/
│   └── TournamentScraper.scala # HTML parsing
├── services/
│   ├── TournamentService.scala    # Tournament transformations
│   └── FlightFilterService.scala  # Quote filtering
└── shell/
    ├── AppState.scala         # Immutable app state
    ├── ShellCommand.scala     # Command ADT + parser
    ├── CommandExecutor.scala  # Command handlers
    └── InteractiveShell.scala # REPL loop
```

---

## 7. Building

```bash
# Compile
sbt compile

# Run
sbt run

# Build fat JAR
sbt assembly
```

---

## License

MIT
