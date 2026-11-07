package com.littlebots.quickpoll;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class Main {
    private static final Logger LOG = Logger.getLogger(Main.class.getName());

    private Main() {}

    public static void main(String[] args) throws InterruptedException {
        Properties local = loadDotEnv();
        String token = value("DISCORD_TOKEN", local).strip();
        String guildId = value("DISCORD_GUILD_ID", local).strip();
        if (token.isBlank() || token.equals("paste-your-bot-token-here")) {
            throw new IllegalStateException("Missing DISCORD_TOKEN. Copy .env.example to .env and add your bot token.");
        }

        PollStore store = new PollStore("polls.db");
        JDA jda = JDABuilder.createDefault(token)
                .addEventListeners(new QuickPollBot(store, guildId), new StartupLogger())
                .build();
        jda.awaitReady();
        LOG.info("Quick Poll is online. Keep this process running while people use the bot.");
    }

    private static Properties loadDotEnv() {
        Properties properties = new Properties();
        Path envFile = Path.of(".env");
        if (!Files.exists(envFile)) return properties;
        try (InputStream input = Files.newInputStream(envFile)) {
            properties.load(input);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read .env", exception);
        }
        return properties;
    }

    private static String value(String key, Properties local) {
        String environmentValue = System.getenv(key);
        return environmentValue != null ? environmentValue : local.getProperty(key, "");
    }

    private static final class StartupLogger extends ListenerAdapter {
        @Override
        public void onReady(ReadyEvent event) {
            QuickPollBot.logStartup(event.getJDA());
        }
    }
}
