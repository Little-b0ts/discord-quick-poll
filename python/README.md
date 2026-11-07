# Python version

## Requirements

- Python 3.10 or newer
- A Discord application and bot token (see the [main setup guide](../README.md#discord-app-setup-one-time))

## Setup

Open a terminal in this `python` folder. Create a virtual environment and install the one dependency:

```powershell
py -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r requirements.txt
Copy-Item .env.example .env
```

On macOS/Linux, activate with `source .venv/bin/activate` and copy with `cp .env.example .env`.

Edit `.env` and paste your token. For quick command updates while developing, also set your test server ID:

```env
DISCORD_TOKEN=paste-your-bot-token-here
DISCORD_GUILD_ID=123456789012345678
```

Keep `.env` private; it is ignored by Git. Run the bot:

```sh
python bot.py
```

When you are ready to invite the bot to more servers, remove the `DISCORD_GUILD_ID` value and restart. Global slash commands can take a little while to appear everywhere.

## Use it

In your server, type `/poll`, enter a question and at least two choices, then submit. Click a choice to vote. Click it again or choose a different option to change your vote. Only the poll creator can close it.

The first run creates `polls.db` in this folder. Do not delete it if you want to keep poll history and votes.
