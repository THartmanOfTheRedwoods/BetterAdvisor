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
 * PaymentCommand - Process student payment
 */
public class PaymentCommand extends BaseCommand {
    private ObservableStudent student;
    private double amount;
    private String paymentType;
    private NotificationManager notificationManager;
    private int paymentId;

    public PaymentCommand(ObservableStudent student, double amount, String paymentType) {
        super();
        this.student = student;
        this.amount = amount;
        this.paymentType = paymentType;
        this.notificationManager = NotificationManager.getInstance();
    }

    @Override
    public void execute() {
        executionTime = LocalDateTime.now();

        // Validate amount
        if (amount <= 0) {
            System.out.println("✗ Payment failed - invalid amount");
            successful = false;
            return;
        }

        // Process payment (simplified - no actual payment gateway)
        paymentId = persistPayment("COMPLETED");

        if (paymentId > 0) {
            executed = true;
            successful = true;

            System.out.println(String.format("✓ Payment processed: $%.2f via %s", amount, paymentType));
            notificationManager.notifyPaymentReceived(student, amount, paymentType);
        } else {
            successful = false;
        }
    }

    @Override
    public void undo() {
        if (!executed || !successful) return;

        // Mark as refunded
        updatePaymentStatus("REFUNDED");

        System.out.println(String.format("↶ Undone: Payment refunded $%.2f", amount));
    }

    @Override
    public boolean isUndoable() {
        return executed && successful;
    }

    @Override
    public String getDescription() {
        return String.format("Payment of $%.2f (%s)", amount, paymentType);
    }

    private int persistPayment(String status) {
        /*
        try {
            String sql = "INSERT INTO payments (student_id, amount, payment_date, status) " +
                    "VALUES (?, ?, ?, ?)";
            PreparedStatement pstmt = DatabaseManager.getInstance().getConnection()
                    .prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            pstmt.setInt(1, student.getId());
            pstmt.setDouble(2, amount);
            pstmt.setTimestamp(3, Timestamp.valueOf(executionTime));
            pstmt.setString(4, status);
            pstmt.executeUpdate();

            ResultSet rs = pstmt.getGeneratedKeys();
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            System.err.println("Error processing payment: " + e.getMessage());
        }
        */
        return -1;
    }

    private void updatePaymentStatus(String status) {
        /*
        try {
            String sql = "UPDATE payments SET status = ? WHERE id = ?";
            PreparedStatement pstmt = DatabaseManager.getInstance().getConnection()
                    .prepareStatement(sql);
            pstmt.setString(1, status);
            pstmt.setInt(2, paymentId);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Error updating payment: " + e.getMessage());
        }
        */
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