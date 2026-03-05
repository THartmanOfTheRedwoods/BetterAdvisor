import edu.advising.core.DatabaseManager;
import edu.advising.notifications.*;
import edu.advising.users.Student;
import edu.advising.users.UserFactory;

import java.sql.SQLException;
import java.util.List;

// Test driver
public class Week4Test {
    public static void main(String[] args) {
        System.out.println("=== WEEK 4: OBSERVER PATTERN DEMO ===\n");
        // Create a user factory to access/create users.
        UserFactory factory = new UserFactory();

        // Create notification manager
        NotificationManager notificationManager = NotificationManager.getInstance();

        // Create students
        Student angie;
        try {
            System.out.println("Creating Angie");
            angie = (Student)factory.createUser("STUDENT", "angie", "P@ss123!",
                    "angie@college.edu", "Angie", "Sandoval", "S54322");
        } catch (RuntimeException re) {
            System.out.println(re.getMessage());
            angie = (Student)factory.getUserByUsername("angie");
        }
        Student bob;
        try {
            System.out.println("Creating Bob");
            bob = (Student)factory.createUser("STUDENT", "bob", "pass456",
                    "bob@college.edu", "Bob", "Smith", "S12347");
        } catch (RuntimeException re) {
            System.out.println(re.getMessage());
            bob = (Student)factory.getUserByUsername("bob");
        }

        // Let's set up some preferences for our users
        try {
            NotificationPreferences preferences = new NotificationPreferences(angie.getId());
            preferences.addNotificationPref(new NotificationPref("GRADE_CHANGE", angie.getId()));
            preferences.addNotificationPref(
                    new NotificationPref("REGISTRATION", angie.getId(),
                            true, false, false, "IMMEDIATE"));
            preferences.addNotificationPref(
                    new NotificationPref("PAYMENT", angie.getId(),
                            false, true, false, "IMMEDIATE"));
            preferences.addNotificationPref(
                    new NotificationPref("FINANCIAL_AID", angie.getId(),
                            false, true, true, "DIGEST"));
            preferences.addNotificationPref(
                    new NotificationPref("DOCUMENT", angie.getId(),
                            false, true, true, "DISABLED"));
            preferences.addNotificationPref(new NotificationPref("RESTRICTION", angie.getId()));
            preferences.addNotificationPref(new NotificationPref("WAITLIST", angie.getId()));
            preferences.saveNotificationPreferences();
        } catch (SQLException se) {
            se.printStackTrace();
        }

        // Let's use the ObservableStudent's factory method to convert these Student objects to Observable objects.
        ObservableStudent angie_obs = ObservableStudent.fromSuperType(angie);
        ObservableStudent bob_obs = ObservableStudent.fromSuperType(bob);

        // Attach observers
        notificationManager.attach(angie_obs);
        notificationManager.attach(bob_obs);

        System.out.println("Observer count: " + notificationManager.getObserverCount() + "\n");

        // Simulate various events
        System.out.println("--- Grade Change Event ---");
        notificationManager.notifyGradeChange(angie, "CS101", "A");

        System.out.println("\n--- Registration Event ---");
        notificationManager.notifyRegistration(angie, "CS201", true);
        notificationManager.notifyRegistration(bob, "MATH301", false);

        System.out.println("\n--- Payment Event ---");
        notificationManager.notifyPaymentReceived(angie, 1500.00, "Credit Card");

        System.out.println("\n--- Financial Aid Event ---");
        notificationManager.notifyFinancialAid(bob, "Pell Grant", "APPROVED", 3000.00);

        System.out.println("\n--- Document Available ---");
        notificationManager.notifyDocumentAvailable(angie, "Fall 2024 Transcript", "PDF");

        System.out.println("\n--- Restriction Alert ---");
        notificationManager.notifyRestriction(bob, "FINANCIAL_HOLD", "Outstanding balance of $500");

        System.out.println("\n--- WaitlistEntry Update ---");
        notificationManager.notifyWaitlistUpdate(angie, "CS301", 3);

        System.out.println("\n--- System-wide Broadcast ---");
        notificationManager.broadcast("SYSTEM", "Registration opens Monday at 8am", "HIGH");

        // Display notifications
        System.out.println("\n" + "=".repeat(50));
        angie_obs.viewNotifications();  // Note, you need the Observer object to access Observable methods.

        System.out.println("\n" + "=".repeat(50));
        System.out.println("\n=== BOB'S NOTIFICATIONS ===");
        System.out.println("Total: " + bob_obs.getNotifications().size());
        System.out.println("Unread: " + bob_obs.getUnreadNotifications().size());

        // Test notification preferences
        System.out.println("\n--- Testing Notification Preferences ---");
        angie_obs.getPreferences().disableNotificationType("PAYMENT");
        angie_obs.getPreferences().getNotificationPref("PAYMENT")
                .ifPresent(np -> np.setSmsEnabled(true));
        try {
            angie_obs.getPreferences().saveNotificationPreferences();
        } catch (SQLException se) {
            se.printStackTrace();
        }

        System.out.println("\nAngie disabled PAYMENT notifications:");
        notificationManager.notifyPaymentReceived(angie, 500.00, "Check");

        System.out.println("\nAngie enabled SMS for HIGH priority:");
        notificationManager.notifyGradeChange(angie, "CS202", "A-");

        // Detach observer
        System.out.println("\n--- Detaching Observer ---");
        notificationManager.detach(bob_obs);
        notificationManager.notifyRegistration(bob, "ENG101", true);
        System.out.println("Bob should not receive this notification (detached)");

        // Get notification history from database
        System.out.println("\n--- Notification History (from DB) ---");
        List<Notification> history = notificationManager.getNotificationHistory(angie.getId(), 5);
        System.out.println("Last 5 notifications for Angie:");
        for (Notification n : history) {
            System.out.println("- " + n.toString());
        }

        DatabaseManager.getInstance().shutdown();
    }
}