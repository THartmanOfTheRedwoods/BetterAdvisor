package edu.advising.commands;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.advising.core.DatabaseManager;
import edu.advising.notifications.ObservableStudent;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * UpdateContactCommand - Update student contact information
 */
public class UpdateContactCommand extends BaseCommand {
    private ObservableStudent student;
    private String newEmail;
    private String newPhone;
    private String oldEmail;
    private String oldPhone;

    public UpdateContactCommand(ObservableStudent student, String newEmail, String newPhone) {
        super();
        this.student = student;
        this.newEmail = newEmail;
        this.newPhone = newPhone;
        // Store old values for undo
        this.oldEmail = student.getEmail();
        this.oldPhone = ""; // Would retrieve from DB in real implementation
    }

    @Override
    public void execute() {
        executionTime = LocalDateTime.now();
        /*
        try {
            String sql = "UPDATE students SET email = ? WHERE id = ?";
            PreparedStatement pstmt = DatabaseManager.getInstance().getConnection()
                    .prepareStatement(sql);
            pstmt.setString(1, newEmail);
            pstmt.setInt(2, student.getId());
            pstmt.executeUpdate();

            executed = true;
            successful = true;

            System.out.println(String.format("✓ Contact info updated for %s", student.getStudentId()));
        } catch (SQLException e) {
            successful = false;
            System.err.println("Error updating contact: " + e.getMessage());
        }
        */
    }

    @Override
    public void undo() {
        if (!executed || !successful) return;
        /*
        try {
            String sql = "UPDATE students SET email = ? WHERE id = ?";
            PreparedStatement pstmt = DatabaseManager.getInstance().getConnection()
                    .prepareStatement(sql);
            pstmt.setString(1, oldEmail);
            pstmt.setInt(2, student.getId());
            pstmt.executeUpdate();

            System.out.println("↶ Undone: Contact info restored");
        } catch (SQLException e) {
            System.err.println("Error undoing contact update: " + e.getMessage());
        }
        */
    }

    @Override
    public boolean isUndoable() {
        return executed && successful;
    }

    @Override
    public String getDescription() {
        return "Update contact information";
    }

    @Override
    protected String serializeCommandData() {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> data = new HashMap<>();
        try {
            return mapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize RegisterCommand data", e);
        }
    }

    @Override
    protected void deserializeCommandData(String json) {
        ObjectMapper mapper = new ObjectMapper();
        try {
            Map<String, Object> data = mapper.readValue(json, Map.class);
            // Reconstruct student, section, etc. from the data
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize RegisterCommand data", e);
        }
    }
}

