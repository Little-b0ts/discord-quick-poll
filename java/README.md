# Java version

## Requirements

- Java 17 or newer
- Maven 3.9 or newer
- A Discord application and bot token (see the [main setup guide](../README.md#discord-app-setup-one-time))

## Setup

Copy `.env.example` to `.env` in this folder and fill in the bot token. Keep that file private; it is ignored by Git.

```powershell
Copy-Item .env.example .env
```

On macOS/Linux, use `cp .env.example .env`.

For quick command updates while developing, set `DISCORD_GUILD_ID` in `.env` to your test server's ID. Leave it blank to register `/poll` globally. Run the bot from this folder:

```sh
mvn compile exec:java
```

Maven downloads JDA and SQLite JDBC the first time. Global slash commands can take a little while to appear everywhere.

## Use it

In your server, type `/poll`, enter a question and at least two choices, then submit. Click a choice to vote. You can change your vote at any time. Only the poll creator can close it.

The first run creates `polls.db` in this folder. Keep it to preserve poll history and votes. Open polls and their buttons continue working after a bot restart.
