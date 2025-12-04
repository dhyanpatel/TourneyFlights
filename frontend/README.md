# TourneyFlights Frontend

A Vue 3 + TypeScript application for searching flight quotes to table tennis tournaments.

## Tech Stack

- **Vue 3** with Composition API and `<script setup>`
- **TypeScript** for type safety
- **Pinia** for state management
- **Naive UI** for UI components
- **Vite** for development and building

## Features

- **API Key Setup**: Enter one or more SerpAPI keys to create a session
- **Flight Search**: Browse and filter flight quotes with:
  - Search by tournament name
  - Filter by airport, state, max price
  - Toggle friends-only airports
  - Toggle pre-filtered results
  - Limit number of results
- **Session Management**: End session and start fresh

## Project Setup

```sh
npm install
```

### Development

Make sure the backend server is running on `http://localhost:8080`, then:

```sh
npm run dev
```

The frontend will be available at `http://localhost:5173/` with API requests proxied to the backend.

### Type Check

```sh
npm run type-check
```

### Build for Production

```sh
npm run build
```

## API Integration

The app consumes the TourneyFlights API:

1. `POST /api/session` - Create session with API keys
2. `GET /api/summary` - Get session statistics
3. `GET /api/airports` - List destination airports
4. `GET /api/states` - List states/regions
5. `GET /api/tournaments` - List tournament names
6. `GET /api/quotes` - Get flight quotes with filters
7. `DELETE /api/session` - End session
