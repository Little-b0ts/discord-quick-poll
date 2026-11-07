package com.littlebots.quickpoll;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class PollStore {
    record Poll(String id, String messageId, String question, String creatorId,
                List<String> options, Map<Integer, Integer> counts, boolean closed) {}

    private final String jdbcUrl;

    PollStore(String databasePath) {
        this.jdbcUrl = "jdbc:sqlite:" + databasePath;
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("""
                CREATE TABLE IF NOT EXISTS polls (
                    poll_id TEXT PRIMARY KEY,
                    message_id TEXT,
                    question TEXT NOT NULL,
                    creator_id TEXT NOT NULL,
                    closed INTEGER NOT NULL DEFAULT 0
                )
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS poll_options (
                    poll_id TEXT NOT NULL REFERENCES polls(poll_id) ON DELETE CASCADE,
                    option_index INTEGER NOT NULL,
                    label TEXT NOT NULL,
                    PRIMARY KEY (poll_id, option_index)
                )
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS votes (
                    poll_id TEXT NOT NULL REFERENCES polls(poll_id) ON DELETE CASCADE,
                    user_id TEXT NOT NULL,
                    option_index INTEGER NOT NULL,
                    PRIMARY KEY (poll_id, user_id)
                )
                """);
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not initialize polls.db", exception);
        }
    }

    private Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
        }
        return connection;
    }

    synchronized void create(String id, String question, String creatorId, List<String> options) {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try (PreparedStatement poll = connection.prepareStatement(
                    "INSERT INTO polls (poll_id, question, creator_id) VALUES (?, ?, ?)")) {
                poll.setString(1, id);
                poll.setString(2, question);
                poll.setString(3, creatorId);
                poll.executeUpdate();
            }
            try (PreparedStatement option = connection.prepareStatement(
                    "INSERT INTO poll_options (poll_id, option_index, label) VALUES (?, ?, ?)")) {
                for (int index = 0; index < options.size(); index++) {
                    option.setString(1, id);
                    option.setInt(2, index);
                    option.setString(3, options.get(index));
                    option.addBatch();
                }
                option.executeBatch();
            }
            connection.commit();
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not save poll", exception);
        }
    }

    synchronized void setMessageId(String pollId, String messageId) {
        update("UPDATE polls SET message_id = ? WHERE poll_id = ?", messageId, pollId);
    }

    synchronized Poll get(String pollId) {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT message_id, question, creator_id, closed FROM polls WHERE poll_id = ?")) {
            statement.setString(1, pollId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null;
                String messageId = result.getString("message_id");
                String question = result.getString("question");
                String creatorId = result.getString("creator_id");
                boolean closed = result.getInt("closed") != 0;
                List<String> options = new ArrayList<>();
                try (PreparedStatement optionQuery = connection.prepareStatement(
                        "SELECT label FROM poll_options WHERE poll_id = ? ORDER BY option_index")) {
                    optionQuery.setString(1, pollId);
                    try (ResultSet optionRows = optionQuery.executeQuery()) {
                        while (optionRows.next()) options.add(optionRows.getString("label"));
                    }
                }
                Map<Integer, Integer> counts = new HashMap<>();
                try (PreparedStatement countQuery = connection.prepareStatement(
                        "SELECT option_index, COUNT(*) AS total FROM votes WHERE poll_id = ? GROUP BY option_index")) {
                    countQuery.setString(1, pollId);
                    try (ResultSet countRows = countQuery.executeQuery()) {
                        while (countRows.next()) counts.put(countRows.getInt("option_index"), countRows.getInt("total"));
                    }
                }
                return new Poll(pollId, messageId, question, creatorId, List.copyOf(options), Map.copyOf(counts), closed);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not read poll", exception);
        }
    }

    synchronized List<Poll> openPolls() {
        List<String> ids = new ArrayList<>();
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT poll_id FROM polls WHERE closed = 0 AND message_id IS NOT NULL");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) ids.add(rows.getString(1));
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not load open polls", exception);
        }
        return ids.stream().map(this::get).toList();
    }

    synchronized boolean vote(String pollId, String userId, int optionIndex) {
        Poll poll = get(pollId);
        if (poll == null || poll.closed() || optionIndex < 0 || optionIndex >= poll.options().size()) return false;
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO votes (poll_id, user_id, option_index) VALUES (?, ?, ?) " +
                     "ON CONFLICT(poll_id, user_id) DO UPDATE SET option_index = excluded.option_index")) {
            statement.setString(1, pollId);
            statement.setString(2, userId);
            statement.setInt(3, optionIndex);
            statement.executeUpdate();
            return true;
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not save vote", exception);
        }
    }

    synchronized boolean close(String pollId) {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE polls SET closed = 1 WHERE poll_id = ? AND closed = 0")) {
            statement.setString(1, pollId);
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not close poll", exception);
        }
    }

    private void update(String sql, String first, String second) {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, first);
            statement.setString(2, second);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not update poll", exception);
        }
    }
}
