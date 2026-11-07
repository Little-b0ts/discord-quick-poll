package com.littlebots.quickpoll;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

final class QuickPollBot extends ListenerAdapter {
    private static final Logger LOG = Logger.getLogger(QuickPollBot.class.getName());
    private static final Color LIME = new Color(184, 221, 85);
    private final PollStore store;
    private final String developmentGuildId;

    QuickPollBot(PollStore store, String developmentGuildId) {
        this.store = store;
        this.developmentGuildId = developmentGuildId;
    }

    @Override
    public void onReady(ReadyEvent event) {
        SlashCommandData command = pollCommand();
        if (developmentGuildId.isBlank()) {
            event.getJDA().upsertCommand(command).queue(
                    ignored -> LOG.info("Registered global /poll command"),
                    error -> LOG.log(Level.SEVERE, "Could not register /poll", error));
            for (PollStore.Poll poll : store.openPolls()) {
                LOG.info("Restored open poll " + poll.id());
            }
            return;
        }

        Guild guild = event.getJDA().getGuildById(developmentGuildId);
        if (guild == null) {
            LOG.severe("DISCORD_GUILD_ID was set, but the bot is not in that server. Check the server ID and invite the bot.");
            return;
        }
        guild.upsertCommand(command).queue(
                ignored -> LOG.info("Registered development /poll command in " + guild.getName()),
                error -> LOG.log(Level.SEVERE, "Could not register /poll", error));
        LOG.info("Development guild commands update immediately; open polls are stored in polls.db.");
    }

    private SlashCommandData pollCommand() {
        OptionData question = new OptionData(OptionType.STRING, "question", "What should people vote on?", true)
                .setRequiredLength(1, 200);
        OptionData choice1 = choiceOption("choice_1", "First choice", true);
        OptionData choice2 = choiceOption("choice_2", "Second choice", true);
        OptionData choice3 = choiceOption("choice_3", "Optional third choice", false);
        OptionData choice4 = choiceOption("choice_4", "Optional fourth choice", false);
        return Commands.slash("poll", "Create a quick poll with clickable choices")
                .setContexts(InteractionContextType.GUILD)
                .addOptions(question, choice1, choice2, choice3, choice4);
    }

    private OptionData choiceOption(String name, String description, boolean required) {
        return new OptionData(OptionType.STRING, name, description, required).setRequiredLength(1, 80);
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("poll")) return;
        if (event.getGuild() == null) {
            event.reply("Polls can only be created in a server channel.").setEphemeral(true).queue();
            return;
        }

        String question = event.getOption("question").getAsString().strip();
        List<String> options = new ArrayList<>();
        for (int index = 1; index <= 4; index++) {
            OptionMapping option = event.getOption("choice_" + index);
            if (option != null) {
                String label = option.getAsString().strip();
                if (!label.isEmpty()) options.add(label);
            }
        }
        Set<String> normalized = new HashSet<>();
        for (String option : options) {
            if (!normalized.add(option.toLowerCase(java.util.Locale.ROOT))) {
                event.reply("Each choice needs a different label.").setEphemeral(true).queue();
                return;
            }
        }
        if (question.isEmpty() || options.size() < 2) {
            event.reply("Add a question and at least two non-empty choices.").setEphemeral(true).queue();
            return;
        }

        String pollId = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        try {
            store.create(pollId, question, event.getUser().getId(), options);
            PollStore.Poll poll = store.get(pollId);
            event.replyEmbeds(render(poll)).addComponents(ActionRow.of(buttons(poll))).queue(
                    hook -> hook.retrieveOriginal().queue(
                            message -> store.setMessageId(pollId, message.getId()),
                            error -> LOG.log(Level.WARNING, "Poll was posted but its message ID could not be saved", error)),
                    error -> {
                        store.close(pollId);
                        LOG.log(Level.WARNING, "Could not post poll", error);
                    });
        } catch (RuntimeException error) {
            LOG.log(Level.SEVERE, "Could not create poll", error);
            event.reply("I couldn't save that poll. Check that the bot can write to its local data folder.").setEphemeral(true).queue();
        }
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String[] parts = event.getComponentId().split(":");
        if (parts.length < 3 || !parts[0].equals("quickpoll")) return;
        String pollId = parts[1];
        PollStore.Poll poll = store.get(pollId);
        if (poll == null) {
            event.reply("I can't find this poll in the local database. Make sure polls.db is still in place.")
                    .setEphemeral(true).queue();
            return;
        }
        if (parts[2].equals("vote") && parts.length == 4) {
            int index;
            try {
                index = Integer.parseInt(parts[3]);
            } catch (NumberFormatException error) {
                event.reply("That poll button is invalid.").setEphemeral(true).queue();
                return;
            }
            if (!store.vote(pollId, event.getUser().getId(), index)) {
                event.reply("This poll is closed.").setEphemeral(true).queue();
                return;
            }
            updatePollMessage(event, pollId);
            return;
        }
        if (parts[2].equals("close") && parts.length == 3) {
            if (!poll.creatorId().equals(event.getUser().getId())) {
                event.reply("Only the person who started this poll can close it.").setEphemeral(true).queue();
                return;
            }
            store.close(pollId);
            updatePollMessage(event, pollId);
        }
    }

    private void updatePollMessage(ButtonInteractionEvent event, String pollId) {
        PollStore.Poll latest = store.get(pollId);
        event.editMessageEmbeds(render(latest)).setComponents(ActionRow.of(buttons(latest))).queue(
                ignored -> {},
                error -> LOG.log(Level.WARNING, "Could not update poll message", error));
    }

    private List<Button> buttons(PollStore.Poll poll) {
        List<Button> buttons = new ArrayList<>();
        for (int index = 0; index < poll.options().size(); index++) {
            buttons.add(Button.primary("quickpoll:" + poll.id() + ":vote:" + index, poll.options().get(index))
                    .withDisabled(poll.closed()));
        }
        buttons.add(Button.secondary("quickpoll:" + poll.id() + ":close", "Close poll")
                .withDisabled(poll.closed()));
        return buttons;
    }

    private net.dv8tion.jda.api.entities.MessageEmbed render(PollStore.Poll poll) {
        int total = poll.counts().values().stream().mapToInt(Integer::intValue).sum();
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("📊 " + poll.question())
                .setDescription(poll.closed()
                        ? "🔒 **Poll closed** · Final results"
                        : "Click a button to vote · You can change your vote anytime")
                .setColor(LIME)
                .setFooter("Started by user " + poll.creatorId() + " · " + total + " total vote" + (total == 1 ? "" : "s"));

        for (int index = 0; index < poll.options().size(); index++) {
            String label = poll.options().get(index);
            int count = poll.counts().getOrDefault(index, 0);
            int percent = total == 0 ? 0 : Math.round(count * 100.0f / total);
            int filled = Math.round(percent / 10.0f);
            String bar = "▰".repeat(filled) + "▱".repeat(10 - filled);
            String votes = count + " vote" + (count == 1 ? "" : "s");
            embed.addField((index + 1) + ". " + label, bar + " **" + percent + "%** · " + votes, false);
        }
        return embed.build();
    }

    static void logStartup(JDA jda) {
        LOG.info("Connected to Discord as " + jda.getSelfUser().getName());
    }
}
