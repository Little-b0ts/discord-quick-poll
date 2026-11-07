from __future__ import annotations

import logging
import os
import sqlite3
import uuid
from contextlib import contextmanager
from pathlib import Path

import discord
from discord import app_commands


logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s: %(message)s",
)
log = logging.getLogger("little-b0ts.quick-poll")
BASE_DIR = Path(__file__).resolve().parent
DATABASE = BASE_DIR / "polls.db"
MAX_CHOICES = 4


def load_local_env() -> None:
    """Load simple KEY=value entries from .env without extra dependencies."""
    env_file = BASE_DIR / ".env"
    if not env_file.exists():
        return
    for raw_line in env_file.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        value = value.strip().strip("\"'")
        if key:
            os.environ.setdefault(key, value)


class PollStore:
    """Small SQLite store; each operation uses its own short-lived connection."""

    def __init__(self, path: Path) -> None:
        self.path = path
        with self.connect() as db:
            db.executescript(
                """
                CREATE TABLE IF NOT EXISTS polls (
                    poll_id TEXT PRIMARY KEY,
                    message_id TEXT,
                    question TEXT NOT NULL,
                    creator_id TEXT NOT NULL,
                    closed INTEGER NOT NULL DEFAULT 0
                );
                CREATE TABLE IF NOT EXISTS poll_options (
                    poll_id TEXT NOT NULL REFERENCES polls(poll_id) ON DELETE CASCADE,
                    option_index INTEGER NOT NULL,
                    label TEXT NOT NULL,
                    PRIMARY KEY (poll_id, option_index)
                );
                CREATE TABLE IF NOT EXISTS votes (
                    poll_id TEXT NOT NULL REFERENCES polls(poll_id) ON DELETE CASCADE,
                    user_id TEXT NOT NULL,
                    option_index INTEGER NOT NULL,
                    PRIMARY KEY (poll_id, user_id)
                );
                """
            )

    @contextmanager
    def connect(self):
        db = sqlite3.connect(self.path, timeout=10)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA foreign_keys = ON")
        try:
            yield db
            db.commit()
        except Exception:
            db.rollback()
            raise
        finally:
            db.close()

    def create(self, poll_id: str, question: str, creator_id: int, options: list[str]) -> None:
        with self.connect() as db:
            db.execute(
                "INSERT INTO polls (poll_id, question, creator_id) VALUES (?, ?, ?)",
                (poll_id, question, str(creator_id)),
            )
            db.executemany(
                "INSERT INTO poll_options (poll_id, option_index, label) VALUES (?, ?, ?)",
                [(poll_id, index, label) for index, label in enumerate(options)],
            )

    def set_message_id(self, poll_id: str, message_id: int) -> None:
        with self.connect() as db:
            db.execute("UPDATE polls SET message_id = ? WHERE poll_id = ?", (str(message_id), poll_id))

    def get(self, poll_id: str) -> dict | None:
        with self.connect() as db:
            poll = db.execute("SELECT * FROM polls WHERE poll_id = ?", (poll_id,)).fetchone()
            if poll is None:
                return None
            options = db.execute(
                "SELECT label FROM poll_options WHERE poll_id = ? ORDER BY option_index", (poll_id,)
            ).fetchall()
            votes = db.execute(
                "SELECT option_index, COUNT(*) AS total FROM votes WHERE poll_id = ? GROUP BY option_index",
                (poll_id,),
            ).fetchall()
        result = dict(poll)
        result["options"] = [row["label"] for row in options]
        result["counts"] = {row["option_index"]: row["total"] for row in votes}
        return result

    def open_polls(self) -> list[dict]:
        with self.connect() as db:
            rows = db.execute(
                "SELECT poll_id, message_id FROM polls WHERE closed = 0 AND message_id IS NOT NULL"
            ).fetchall()
        return [dict(row) for row in rows]

    def vote(self, poll_id: str, user_id: int, option_index: int) -> bool:
        with self.connect() as db:
            poll = db.execute("SELECT closed FROM polls WHERE poll_id = ?", (poll_id,)).fetchone()
            if poll is None or poll["closed"]:
                return False
            db.execute(
                "INSERT INTO votes (poll_id, user_id, option_index) VALUES (?, ?, ?) "
                "ON CONFLICT(poll_id, user_id) DO UPDATE SET option_index = excluded.option_index",
                (poll_id, str(user_id), option_index),
            )
        return True

    def close(self, poll_id: str) -> bool:
        with self.connect() as db:
            cursor = db.execute("UPDATE polls SET closed = 1 WHERE poll_id = ? AND closed = 0", (poll_id,))
            return cursor.rowcount == 1


store = PollStore(DATABASE)


def make_embed(poll: dict) -> discord.Embed:
    total = sum(poll["counts"].values())
    lines = []
    for index, label in enumerate(poll["options"]):
        count = poll["counts"].get(index, 0)
        percent = round(count * 100 / total) if total else 0
        bar = "▰" * round(percent / 10) + "▱" * (10 - round(percent / 10))
        lines.append(f"**{index + 1}. {discord.utils.escape_markdown(label)}**\n`{bar}` **{percent}%** · {count} vote{'s' if count != 1 else ''}")
    description = "\n\n".join(lines)
    status = "🔒 Poll closed" if poll["closed"] else "Click a button to vote · You can change your vote anytime"
    embed = discord.Embed(title=f"📊 {poll['question']}", description=f"{description}\n\n{status}", color=0xB8DD55)
    embed.set_footer(text=f"Started by {poll['creator_id']} · {total} total vote{'s' if total != 1 else ''}")
    return embed


class VoteButton(discord.ui.Button):
    def __init__(self, poll_id: str, index: int, label: str, disabled: bool = False) -> None:
        super().__init__(
            label=label,
            style=discord.ButtonStyle.primary,
            custom_id=f"quickpoll:{poll_id}:vote:{index}",
            disabled=disabled,
        )
        self.poll_id = poll_id
        self.index = index

    async def callback(self, interaction: discord.Interaction) -> None:
        poll = store.get(self.poll_id)
        if poll is None or poll["closed"]:
            await interaction.response.send_message("This poll is closed or no longer available.", ephemeral=True)
            return
        if not store.vote(self.poll_id, interaction.user.id, self.index):
            await interaction.response.send_message("This poll is closed.", ephemeral=True)
            return
        updated = store.get(self.poll_id)
        await interaction.response.edit_message(embed=make_embed(updated), view=PollView(self.poll_id, updated))


class CloseButton(discord.ui.Button):
    def __init__(self, poll_id: str, creator_id: int, disabled: bool = False) -> None:
        super().__init__(
            label="Close poll",
            style=discord.ButtonStyle.secondary,
            custom_id=f"quickpoll:{poll_id}:close",
            disabled=disabled,
        )
        self.poll_id = poll_id
        self.creator_id = creator_id

    async def callback(self, interaction: discord.Interaction) -> None:
        if interaction.user.id != self.creator_id:
            await interaction.response.send_message("Only the person who started this poll can close it.", ephemeral=True)
            return
        store.close(self.poll_id)
        poll = store.get(self.poll_id)
        await interaction.response.edit_message(embed=make_embed(poll), view=PollView(self.poll_id, poll))


class PollView(discord.ui.View):
    """Persistent view: stable custom IDs let open polls work after restarts."""

    def __init__(self, poll_id: str, poll: dict) -> None:
        super().__init__(timeout=None)
        closed = bool(poll["closed"])
        for index, label in enumerate(poll["options"]):
            self.add_item(VoteButton(poll_id, index, label, disabled=closed))
        self.add_item(CloseButton(poll_id, int(poll["creator_id"]), disabled=closed))


class QuickPollBot(discord.Client):
    def __init__(self) -> None:
        super().__init__(intents=discord.Intents.none())
        self.tree = app_commands.CommandTree(self)

    async def setup_hook(self) -> None:
        for row in store.open_polls():
            poll = store.get(row["poll_id"])
            if poll:
                self.add_view(PollView(row["poll_id"], poll), message_id=int(row["message_id"]))

        guild_id = os.getenv("DISCORD_GUILD_ID", "").strip()
        if guild_id:
            guild = discord.Object(id=int(guild_id))
            self.tree.copy_global_to(guild=guild)
            await self.tree.sync(guild=guild)
            log.info("Synced commands to development server %s", guild_id)
        else:
            await self.tree.sync()
            log.info("Synced global commands")


bot = QuickPollBot()


@bot.tree.command(name="poll", description="Create a quick poll with clickable choices")
@app_commands.guild_only()
@app_commands.describe(
    question="What should people vote on?",
    choice_1="First choice",
    choice_2="Second choice",
    choice_3="Optional third choice",
    choice_4="Optional fourth choice",
)
async def poll_command(
    interaction: discord.Interaction,
    question: app_commands.Range[str, 1, 200],
    choice_1: app_commands.Range[str, 1, 80],
    choice_2: app_commands.Range[str, 1, 80],
    choice_3: app_commands.Range[str, 1, 80] | None = None,
    choice_4: app_commands.Range[str, 1, 80] | None = None,
) -> None:
    options = [value.strip() for value in (choice_1, choice_2, choice_3, choice_4) if value and value.strip()]
    if len({option.casefold() for option in options}) != len(options):
        await interaction.response.send_message("Each choice needs a different label.", ephemeral=True)
        return
    if not question.strip() or len(options) < 2:
        await interaction.response.send_message("Add a question and at least two non-empty choices.", ephemeral=True)
        return

    poll_id = uuid.uuid4().hex[:16]
    store.create(poll_id, question.strip(), interaction.user.id, options)
    poll = store.get(poll_id)
    view = PollView(poll_id, poll)
    try:
        await interaction.response.send_message(embed=make_embed(poll), view=view)
        message = await interaction.original_response()
        store.set_message_id(poll_id, message.id)
    except discord.HTTPException:
        log.exception("Discord could not create a poll message")
        await store.close(poll_id)
        if not interaction.response.is_done():
            await interaction.response.send_message("I couldn't post that poll. Check that I can send messages and embeds here.", ephemeral=True)


@bot.tree.error
async def on_app_command_error(interaction: discord.Interaction, error: app_commands.AppCommandError) -> None:
    log.error("Application command failed", exc_info=error)
    message = "Something went wrong. Please try again, and check the bot's console if it keeps happening."
    if interaction.response.is_done():
        await interaction.followup.send(message, ephemeral=True)
    else:
        await interaction.response.send_message(message, ephemeral=True)


if __name__ == "__main__":
    load_local_env()
    token = os.getenv("DISCORD_TOKEN", "").strip()
    if not token or token == "paste-your-bot-token-here":
        raise SystemExit("Missing DISCORD_TOKEN. Copy .env.example to .env and add your bot token.")
    bot.run(token, log_handler=None)


