package edu.advising.commands;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.advising.core.DatabaseManager;
import edu.advising.notifications.NotificationManager;
import edu.advising.notifications.ObservableStudent;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * WaitlistCommand - Add student to waitlist
 */
public class WaitlistCommand extends BaseCommand {
    private ObservableStudent student;
    private Section section;
    private int waitlistId;
    private NotificationManager notificationManager;

    public WaitlistCommand(ObservableStudent student, Section section) {
        super();
        this.student = student;
        this.section = section;
        this.notificationManager = NotificationManager.getInstance();
    }

    @Override
    public void execute() {
        executionTime = LocalDateTime.now();

        if ((this.waitlistId = section.addToWaitlist(student)) > 0) {
            executed = true;
            successful = true;
            try {
                int position = section.getWaitlistPosition(student);
                System.out.printf("✓ Student %s added to waitlist for %s (Position: #%d)%n",
                        student.getStudentId(), section.getCourseCode(), position);
                notificationManager.notifyWaitlistUpdate(student, section.getCourseCode(), position);
            } catch (SQLException e) {
                System.out.printf("✓ Student %s added to waitlist for %s but couldn't determine position.%n",
                        student.getStudentId(), section.getCourseCode());
                notificationManager.notifyWaitlistUpdate(student, section.getCourseCode(), -1);
            }

        }
    }

    @Override
    public void undo() {
        if (!executed || !successful) {
            System.out.println("Cannot undo - command not executed or failed");
            return;
        }

        if (section.removeFromWaitlist(student)) {
            System.out.printf("↶ Undone: Waitlist for %s%n", section.getCourseCode());
            this.undoneAt = LocalDateTime.now();
            this.isUndone = true;
            // Notify about waitlist removal.
            notificationManager.notifyWaitlistUpdate(student, section.getCourseCode(), Integer.MAX_VALUE);
        }
    }

    @Override
    public boolean isUndoable() {
        return executed && successful;
    }

    @Override
    public String getDescription() {
        return String.format("Add to waitlist for %s", section.getCourseCode());
    }

    @Override
    protected String serializeCommandData() {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> data = new HashMap<>();
        data.put("studentId", student.getStudentId());
        data.put("sectionId", section.getId()); // Assuming Section has an id
        data.put("waitlistId", waitlistId);
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
            this.student = DatabaseManager.getInstance()
                    .fetchOne(ObservableStudent.class, "id", data.get("studentId"));
            this.section = DatabaseManager.getInstance()
                    .fetchOne(Section.class, "id", data.get("sectionId"));
            this.waitlistId = (int) data.get("waitlistId");
        } catch (JsonProcessingException | SQLException e) {
            throw new RuntimeException("Failed to deserialize RegisterCommand data", e);
        }
    }
}