package edu.advising.commands;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.advising.core.DatabaseManager;
import edu.advising.notifications.ObservableStudent;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MacroCommand - Executes multiple commands as one transaction
 */
public class MacroCommand extends BaseCommand {
    private List<BaseCommand> commands;
    private String description;

    public MacroCommand(String description) {
        super();
        this.commands = new ArrayList<>();
        this.description = description;
    }

    public void addCommand(BaseCommand command) {
        commands.add(command);
    }

    @Override
    public void execute() {
        executionTime = LocalDateTime.now();
        System.out.printf("▶ Executing macro: %s (%d commands)%n",
                description, commands.size());

        boolean allSuccessful = true;
        for (BaseCommand command : commands) {
            command.execute();
            if (!command.wasSuccessful()) {
                allSuccessful = false;
                System.out.println("  ✗ Command failed: " + command.getDescription());
                break;
            }
        }

        executed = true;
        successful = allSuccessful;

        if (successful) {
            System.out.println("✓ Macro completed successfully");
        } else {
            System.out.println("✗ Macro failed - rolling back");
            undo();
        }
    }

    @Override
    public void undo() {
        if (!executed) return;

        System.out.println(String.format("↶ Undoing macro: %s", description));

        // Undo in reverse order
        for (int i = commands.size() - 1; i >= 0; i--) {
            BaseCommand command = commands.get(i);
            if (command.wasSuccessful()) {
                command.undo();
            }
        }
    }

    @Override
    public boolean isUndoable() {
        return executed && commands.stream().allMatch(BaseCommand::isUndoable);
    }

    @Override
    public String getDescription() {
        return String.format("%s (Macro: %d commands)", description, commands.size());
    }

    @Override
    protected String serializeCommandData() {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> data = new HashMap<>();
        for(int i=0; i < this.commands.size(); i++) {
            BaseCommand bc = this.commands.get(i);
            data.put(bc.getClass() + "_"+i, bc.serializeCommandData());
        }
        try {
            return mapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize RegisterCommand data", e);
        }
    }

    protected void deserializeCommandData(String json) {
        ObjectMapper mapper = new ObjectMapper();
        try {
            Map<String, Object> data = mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            this.commands = new ArrayList<>(); // Initialize the list

            // Iterate over the map entries
            for (Map.Entry<String, Object> entry : data.entrySet()) {
                String key = entry.getKey();
                String[] parts = key.split("_"); // Split into class name and index
                if (parts.length < 2) continue; // Skip malformed keys

                String className = parts[0]; // e.g., "class edu.advising.notifications.RegisterCommand"
                int index = Integer.parseInt(parts[1]); // The index (e.g., 0, 1, 2...)

                // Convert the value to a JSON string (if it's a LinkedHashMap from Jackson)
                String commandDataJson = mapper.writeValueAsString(entry.getValue());

                // Create the appropriate BaseCommand subclass
                BaseCommand command = createCommandInstance(className);
                if (command != null) {
                    // Set the command data and deserialize
                    command.setCommandData(commandDataJson);
                    command.deserializeCommandData(commandDataJson);
                    this.commands.add(command);
                }
            }
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize MacroBaseCommand data", e);
        }
    }

    // Helper method to create an instance of the correct BaseCommand subclass
    private BaseCommand createCommandInstance(String className) {
        try {
            Class<?> clazz = Class.forName(className);
            return (BaseCommand) clazz.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create instance of " + className, e);
        }
    }
}
