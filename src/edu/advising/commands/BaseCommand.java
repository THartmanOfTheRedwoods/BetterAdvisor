package edu.advising.commands;

import edu.advising.core.Column;
import edu.advising.core.Id;
import edu.advising.core.Table;

import java.time.LocalDateTime;


/**
 * Abstract base command with common functionality
 */
@Table(name = "command_history")
public abstract class BaseCommand implements Command {
    @Id(isPrimary = true)
    @Column(name = "id", upsertIgnore = true)
    protected int id;
    @Column(name = "user_id", foreignKey = true)
    protected int userId;
    @Column(name = "command_type")
    protected String commandType;
    @Column(name = "command_data")
    protected String commandData;
    @Column(name = "executed_at")
    protected LocalDateTime executionTime;
    @Column(name = "undone_at")
    protected LocalDateTime undoneAt;
    @Column(name = "is_undone")
    protected boolean isUndone;
    @Column(name = "success")
    protected boolean successful;
    @Column(name = "error_message")
    protected String errorMessage;

    protected boolean executed;

    public BaseCommand() {
        this.executed = false;
        this.successful = false;
    }

    /*
    protected abstract String serializeCommandData();

    @Override
    public void execute() {
        System.out.println("BaseCommand does not implement this.");
    }

    @Override
    public void undo() {
        System.out.println("BaseCommand does not implement this.");
    }

    @Override
    public boolean isUndoable() {
        return false;
    }

    @Override
    public String getDescription() {
        return "Abstract-ISH BaseCommand";
    }
    */

    // Getters and Setters for CommandHistory
    @Override
    public LocalDateTime getExecutionTime() {
        return executionTime;
    }

    public void setExecutionTime(LocalDateTime executedAt) {
        this.executionTime = executedAt;
    }

    @Override
    public boolean wasSuccessful() {
        return successful;
    }

    public void setSuccess(boolean success) {
        this.successful = success;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public int getUserId() {
        return userId;
    }

    public void setUserId(int userId) {
        this.userId = userId;
    }

    public String getCommandType() {
        return commandType;
    }

    public void setCommandType(String commandType) {
        this.commandType = commandType;
    }

    public String getCommandData() {
        return commandData;
    }

    public void setCommandData(String commandData) {
        this.commandData = commandData;
    }

    public LocalDateTime getUndoneAt() {
        return undoneAt;
    }

    public void setUndoneAt(LocalDateTime undoneAt) {
        this.undoneAt = undoneAt;
    }

    public boolean isUndone() {
        return isUndone;
    }

    public void setUndone(boolean undone) {
        isUndone = undone;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    // Methods to store and retrieve Command Objects.

    protected abstract String serializeCommandData();

    protected abstract void deserializeCommandData(String json);

    // Call this before saving to the database
    public void prepareForStorage() {
        this.commandData = serializeCommandData();
    }

    // Call this after loading from the database
    public void initAfterLoad() {
        deserializeCommandData(this.commandData);
    }
}