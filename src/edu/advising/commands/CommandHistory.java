package edu.advising.commands;

import edu.advising.core.DatabaseManager;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Stack;

/**
 * CommandHistory - Manages command execution history with undo/redo
 */
public class CommandHistory {

    private Stack<BaseCommand> history;
    private Stack<BaseCommand> redoStack;
    private int maxHistorySize;

    public CommandHistory() {
        this(50); // Default max history
    }

    public CommandHistory(int maxHistorySize) {
        // TODO: Load last maxHistorySize commands from database into Stack when object gets recreated.
        //   Probably should also make this Invoker a singleton to prevent multiple different objects loading the
        //   same history Stack.
        this.history = new Stack<>();
        this.redoStack = new Stack<>();
        this.maxHistorySize = maxHistorySize;
    }

    public void executeCommand(BaseCommand command) {
        // This is an example where the Command object isn't composed in the Invoker, it is passed as a parameter.
        //   Though you could argue that putting it in the history and redo stacks is composing it.
        command.execute();

        if (command.wasSuccessful() && command.isUndoable()) {
            // Persist the command locally
            history.push(command);
            // Persist the command to the database
            try {
                command.prepareForStorage();
                DatabaseManager.getInstance().upsert(command);
            } catch (SQLException | IllegalAccessException e) {
                e.printStackTrace();
                System.out.println("Failed to save to command history.");
            }
            redoStack.clear(); // Clear redo stack on new command

            // Limit history size
            if (history.size() > maxHistorySize) {
                history.remove(0);
            }
        }
    }

    public void undo() {
        if (history.isEmpty()) {
            System.out.println("Nothing to undo");
            return;
        }

        BaseCommand command = history.pop();
        command.undo();
        System.out.println("↶ Undo successful: " + command.getDescription());
        // Persist undo updates to command history.
        try {
            command.prepareForStorage();
            DatabaseManager.getInstance().upsert(command);
        } catch (SQLException | IllegalAccessException e) {
            e.printStackTrace();
            System.out.println("Failed to save undo update to command history.");
        }
        redoStack.push(command);
    }

    public void redo() {
        if (redoStack.isEmpty()) {
            System.out.println("Nothing to redo");
            return;
        }

        BaseCommand command = redoStack.pop();
        command.execute();
        if (command.wasSuccessful()) {
            System.out.println("↷ Redo successful: " + command.getDescription());
            // Persist the command locally
            history.push(command);
            // Persist the command to the database
            try {
                command.prepareForStorage();
                DatabaseManager.getInstance().upsert(command);
            } catch (SQLException | IllegalAccessException e) {
                e.printStackTrace();
                System.out.println("Failed to save redo to command history.");
            }
            // Limit history size
            if (history.size() > maxHistorySize) {
                history.remove(0);
            }
        }
    }

    public void showHistory() {
        System.out.println("\n=== COMMAND HISTORY ===");
        if (history.isEmpty()) {
            System.out.println("No commands in history");
            return;
        }

        System.out.println("Commands (most recent first):");
        List<BaseCommand> historyList = new ArrayList<>(history);
        Collections.reverse(historyList);

        for (int i = 0; i < historyList.size(); i++) {
            BaseCommand cmd = historyList.get(i);
            String status = cmd.wasSuccessful() ? "✓" : "✗";
            System.out.printf("%d. %s %s [%s]%n",
                    i + 1, status, cmd.getDescription(),
                    cmd.getExecutionTime().toString().substring(11, 19));
        }

        System.out.printf("\nTotal commands: %d | Can undo: %d | Can redo: %d%n",
                history.size(), history.size(), redoStack.size());
    }

    public boolean canUndo() {
        return !history.isEmpty();
    }

    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    public void clear() {
        history.clear();
        redoStack.clear();
        System.out.println("Local Command history cleared");
    }
}
