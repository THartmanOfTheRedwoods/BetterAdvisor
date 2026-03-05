package edu.advising.notifications;

import edu.advising.core.DatabaseManager;
import edu.advising.users.Student;
import edu.advising.users.User;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * NotificationManager - Central notification hub (Subject implementation)
 */
public class NotificationManager implements Subject {
    private List<Observer> observers;
    private List<Notification> notificationHistory;
    private DatabaseManager dbManager;
    private static NotificationManager instance;

    private NotificationManager() {
        this.observers = new ArrayList<>();
        this.notificationHistory = new ArrayList<>();
        this.dbManager = DatabaseManager.getInstance();
    }

    public static NotificationManager getInstance() {
        if (instance == null) {
            instance = new NotificationManager();
        }
        return instance;
    }

    @Override
    public void attach(Observer observer) {
        if (!observers.contains(observer)) {
            observers.add(observer);
            System.out.println("✓ Observer attached: User ID " + observer.getUserId());
        }
    }

    @Override
    public void detach(Observer observer) {
        observers.remove(observer);
        System.out.println("✓ Observer detached: User ID " + observer.getUserId());
    }

    @Override
    public void notifyObservers(Notification notification) {
        // Save to database first
        persistNotification(notification);

        // Add to history
        notificationHistory.add(notification);

        // Notify specific observer(s)
        for (Observer observer : observers) {
            if (observer.getUserId() == notification.getUserId()) {
                observer.update(notification);
            }
        }
    }

    /**
     * Broadcast to all observers (system-wide announcements)
     */
    public void broadcast(String type, String message, String priority) {
        for (Observer observer : observers) {
            Notification notification = new Notification(type, message,
                    observer.getUserId(), priority);
            notifyObservers(notification);
        }
    }

    // Specific notification methods for different events

    public void notifyGradeChange(Student student, String courseCode, String grade) {
        Notification notification = new Notification(
                "GRADE_CHANGE",
                String.format("Grade posted for %s: %s", courseCode, grade),
                student.getId(),
                "HIGH"
        );
        notification.addMetadata("courseCode", courseCode);
        notification.addMetadata("grade", grade);
        notifyObservers(notification);
    }

    public void notifyRegistration(Student student, String courseCode, boolean success) {
        String message = success
                ? String.format("Successfully registered for %s", courseCode)
                : String.format("Registration failed for %s", courseCode);

        Notification notification = new Notification(
                "REGISTRATION",
                message,
                student.getId(),
                success ? "MEDIUM" : "HIGH"
        );
        notification.addMetadata("courseCode", courseCode);
        notification.addMetadata("success", String.valueOf(success));
        notifyObservers(notification);
    }

    public void notifyPaymentReceived(Student student, double amount, String paymentType) {
        Notification notification = new Notification(
                "PAYMENT",
                String.format("Payment of $%.2f received (%s)", amount, paymentType),
                student.getId(),
                "MEDIUM"
        );
        notification.addMetadata("amount", String.valueOf(amount));
        notification.addMetadata("paymentType", paymentType);
        notifyObservers(notification);
    }

    public void notifyFinancialAid(Student student, String aidType, String status, double amount) {
        Notification notification = new Notification(
                "FINANCIAL_AID",
                String.format("%s: %s - $%.2f", aidType, status, amount),
                student.getId(),
                "HIGH"
        );
        notification.addMetadata("aidType", aidType);
        notification.addMetadata("status", status);
        notification.addMetadata("amount", String.valueOf(amount));
        notifyObservers(notification);
    }

    public void notifyDocumentAvailable(User user, String documentName, String documentType) {
        Notification notification = new Notification(
                "DOCUMENT",
                String.format("New document available: %s", documentName),
                user.getId(),
                "MEDIUM"
        );
        notification.addMetadata("documentName", documentName);
        notification.addMetadata("documentType", documentType);
        notifyObservers(notification);
    }

    public void notifyRestriction(Student student, String restrictionType, String details) {
        Notification notification = new Notification(
                "RESTRICTION",
                String.format("Account restriction: %s - %s", restrictionType, details),
                student.getId(),
                "HIGH"
        );
        notification.addMetadata("restrictionType", restrictionType);
        notification.addMetadata("details", details);
        notifyObservers(notification);
    }

    public void notifyWaitlistUpdate(Student student, String courseCode, int position) {
        Notification notification = new Notification(
                "WAITLIST",
                String.format("WaitlistEntry update for %s: Position #%d", courseCode, position),
                student.getId(),
                "MEDIUM"
        );
        notification.addMetadata("courseCode", courseCode);
        notification.addMetadata("position", String.valueOf(position));
        notifyObservers(notification);
    }

    /**
     * Get unread notifications for a user
     */
    public List<Notification> getUnreadNotifications(int userId) {
        String sql = "SELECT * FROM notifications WHERE user_id = ? AND read_status = FALSE " +
                "ORDER BY created_at DESC";

        try {
            return dbManager.fetchList(sql, rs -> {
                // This lambda runs ONCE per row found in the database
                Notification n = new Notification(
                        rs.getString("type"),
                        rs.getString("message"),
                        rs.getInt("user_id")
                );
                n.setId(rs.getInt("id"));
                return n;
            }, userId);
        } catch (SQLException e) {
            System.err.println("Error fetching unread notifications: " + e.getMessage());
            return new ArrayList<>(); // Return empty list on failure
        }
    }

    /**
     * Mark notification as read
     */
    public void markAsRead(int notificationId) {
        try {
            String sql = "UPDATE notifications SET read_status = TRUE WHERE id = ?";
            dbManager.executeUpdate(sql, notificationId);
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    /**
     * Get notification history for a user
     */
    public List<Notification> getNotificationHistory(int userId, int limit) {
        try {
            String sql = "SELECT * FROM notifications WHERE user_id = ? " +
                    "ORDER BY created_at DESC LIMIT ?";
            return dbManager.fetchList(sql, rs -> {
                Notification n = new Notification(
                        rs.getString("type"),
                        rs.getString("message"),
                        rs.getInt("user_id")
                );
                n.setId(rs.getInt("id"));
                if (rs.getBoolean("read_status")) {
                    n.markAsRead();
                }
                return n;
            }, userId, limit);
        } catch (SQLException e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    private void persistNotification(Notification notification) {
        try {
            String sql = "INSERT INTO notifications (user_id, message, type, created_at, read_status) " +
                    "VALUES (?, ?, ?, ?, ?)";
            notification.setId(
                    dbManager.executeInsert(sql, notification.getUserId(), notification.getMessage(),
                            notification.getType(), Timestamp.valueOf(notification.getTimestamp()),
                            notification.isRead())
            );
        } catch (SQLException e) {
            System.err.println("Error persisting notification: " + e.getMessage());
        }
    }

    public int getObserverCount() {
        return observers.size();
    }
}
