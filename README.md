# TourneyFlights

A small Scala app that scrapes upcoming USATT table-tennis tournaments and checks flight prices (via SerpApi → Google Flights) for the next 3 months. It outputs a table + CSV so you can quickly see which cities are worth flying to.

---

## 1. Getting a SerpApi API Key

1. Go to [https://serpapi.com](https://serpapi.com)
2. Click **Sign Up**
3. Create an account
4. After logging in, open your Dashboard
5. Copy your API key from the **API Key** section

SerpApi gives you 250 free searches/month, which is enough for a weekly check.

---

## 2. Create Your `.env` File

In the root directory of the repo, create a file called:

```
.env
```

You can use the `.env.example` file as an example of what to fill in.

Notes:

* `FRIEND_AIRPORTS` is a comma-separated list
* Time filters let you block flights with departure times outside acceptable time windows

---

## 3. Download the JAR

On GitHub:

```
Releases → Assets → tourneyFlights.jar
```

---

## 4. Running the Program

Ensure `.env` is in the same directory as the JAR or your run command.

```
java -jar tourneyFlights.jar
```

The app will create a  file called `weekend_quotes.csv` with **all** flights.

To see the “ideal” flights (cheap or in a city with a friend), look at the printed output of the jar file.
