# Quick Poll Bot

A small, beginner-friendly Discord bot template from **Little-b0ts**. It creates native slash-command polls with clickable buttons, lets each person change their vote, and saves poll data in SQLite so polls survive restarts.

Choose **Python** or **Java** below. Both versions provide the same `/poll` experience.

## What it does

- `/poll` creates a public poll with 2–4 choices.
- Members vote with buttons. A member has one active vote and can change it at any time.
- Live totals update on the poll message.
- The person who created a poll can close it. Closed polls remain visible with final totals.
- Polls and votes are saved in a local SQLite database and open poll buttons continue working after a restart.
- It uses slash commands and buttons only. It does not read message content and does not need privileged intents or administrator permissions.

## Pick a version

- [Python setup and source](python/README.md)
- [Java setup and source](java/README.md)

## Discord app setup (one time)

1. In the [Discord Developer Portal](https://discord.com/developers/applications), create an application and add a bot user.
2. Copy the bot token. Keep it private. If it is ever exposed, reset it in the portal.
3. Under **Installation**, enable **Guild Install** and add the `bot` and `applications.commands` scopes. Grant only **Send Messages** and **Embed Links**.
4. Copy the install link, open it, and add the bot to a test server where you can manage apps.
5. Copy your test server's ID (enable Developer Mode in Discord, then right-click the server icon and choose **Copy Server ID**). Set `DISCORD_GUILD_ID` while developing so `/poll` appears quickly. Leave it unset to register the command globally.
6. Add `DISCORD_TOKEN` and optionally `DISCORD_GUILD_ID` to a local `.env` file beside the source. Never commit a real token.

## Local data and hosting

Each version creates `polls.db` in its working directory. Keep that file between restarts and back it up if you host the bot somewhere. If you move the bot to a new machine, move the database too. The bot must be running for people to vote or close a poll; polls remain stored while it is offline.

GitHub Pages can publish the project website, but it cannot keep a Discord bot running. Run the bot on your computer for learning, or use a separate always-on host later.

## Support or Donating
If you need support contact me on discord : spectro309896

If you would like to support my work message me on discord : spectro309896

If you need custom bots, more commands or any suggestions, again contact me on discord : spectro309896

PS: I am not annoying 😉... I love working though...

## License

MIT. See [LICENSE](LICENSE).

