import edu.advising.commands.*;
import edu.advising.core.DatabaseManager;
import edu.advising.notifications.*;
import edu.advising.users.Faculty;
import edu.advising.users.Student;
import edu.advising.users.User;
import edu.advising.users.UserFactory;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

// Test driver
public class Week5Test {
    public static void main(String[] args) {
        System.out.println("=== WEEK 5: PREREQUISITE DEMO ===\n");
        // Grab a copy of the DatabaseManager
        DatabaseManager dbManager = DatabaseManager.getInstance();

        System.out.println("\n=== Fetch just a plain User from the database with new ORM fetch functions. ===\n");
        try {
            User u = dbManager.fetchOne(User.class, "id", 3);
            System.out.println(u.getFullName());
        } catch (SQLException e) {
            e.printStackTrace();
        }

        System.out.println("\n=== Fetch Student and Faculty using ORM. Test new Sup/Sub hierarchy model loading. ===\n");
        try {
            Student s = dbManager.fetchOne(Student.class, "id", 3);
            System.out.printf("%s: GPA: %s%n", s.getFullName(), s.getGpa());
            Faculty f = dbManager.fetchOne(Faculty.class, "id", 4);
            System.out.printf("%s: Dept: %s%n", f.getFullName(), f.getDepartment());
        } catch (SQLException e) {
            e.printStackTrace();
        }

        System.out.println("\n===  Test OneToMany and ManyToOne relationships. ===\n");
        try {
            Course cis12 = dbManager.fetchOne(Course.class, "id", 1);
            List<Section> sections = cis12.getSections();
            System.out.printf("Course Name: %s%n", cis12.getName());
            for(Section s : sections) {
                System.out.printf("Section Number: %s, Semester: %s, Year: %s%n",
                        s.getSectionNumber(), s.getSemester(), s.getYear());
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        System.out.println("\n===  Test ManyToMany relationships. ===\n");
        try {
            // Fetch a student from the database.
            Student s1 = dbManager.fetchOne(Student.class, "student_id", "S54322");
            System.out.println(s1.getFullName());
            // Now get the course sections that student is a member of.
            List<Section> s1Sections = s1.getSections();
            for(Section s : s1Sections) {
                System.out.printf("Course: %s Section: %s Semester: %s Year: %s%n",
                        s.getCourseName(), s.getSectionNumber(), s.getSemester(), s.getYear());
                // Now for each section, look in revers and list the students in each section!
                List<Student> students = s.getEnrolledStudents();
                for(Student es : students) {
                    System.out.printf("    %s%n", es.getFullName());
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        System.out.println("\n===  Test Upsert and UpsertAll with hierarchy relationships. ===\n");
        try {
            // First let me test an Upsert with a single object model to verify single model upsert still works.
            // Since we can't create it without a department, let's see if we can find a department
            Department dept = dbManager.fetchOne(Department.class, "code", "CIS");
            System.out.printf("Found Department: %s%n", dept.getName());
            Course cis18 = new Course("CIS18", "Object Oriented Programming",
                    "This course teaches the Object Oriented Programming paradigm",
                    4, dept.getId(), "100");
            dbManager.upsert(cis18);
            // Now let's see if we have a database id?
            System.out.printf("New Course ID: %d%n", cis18.getId());
            // Now let's see if we can change the department id for this course.
            Department newDept = dbManager.fetchOne(Department.class, "code", "MATH");
            System.out.printf("Found Department: %s%n", newDept.getName());
            cis18.setDepartmentId(newDept.getId()); // NOTE: you must have a 2 in the
            dbManager.upsert(cis18);
            System.out.println("Go check your Database!");

            // Now let's test a hierarchical upsert!
            Student newStudent = new Student("trevor-hartman", "N0tS0Str0ng!", "trevor-hartman@mycr.redwoods.edu",
                    "Trevor", "Hartman", "CR8432");
            dbManager.upsert(newStudent);

            // Will upsert work with Sections embeded into the course now?
            System.out.println("\n===  Test Upsert and UpsertAll with Composite Object Relationships. ===\n");
            List<Section> sections = new ArrayList<>();
            sections.add(new Section("1", "SP", 26, 30));
            sections.add(new Section("1", "FA", 26, 30));
            cis18.setSections(sections);
            dbManager.upsert(cis18);
        } catch (SQLException | IllegalAccessException e) {
            e.printStackTrace();
        }

        System.out.println("=== WEEK 5: COMMAND PATTERN DEMO ===\n");

        try {
            // Create student
            ObservableStudent alice = new ObservableStudent("alice02", "P@ss1234!",
                    "alice02@college.edu", "Alice", "Johnson", "S52345");
            dbManager.upsert(alice);

            // Attach to notification system
            NotificationManager.getInstance().attach(alice);

            // NOTE: For this test you NEED to make sure the courseIds exist in the dB or creating sections will fail.
            // Create sections (Receiver)
            Section cs101 = new Section(12, "1", "FA", 26, 30,
                    0, 2);
            Section cs301 = new Section(13, "1", "FA", 26, 25,
                    0, 2);
            Section math301 = new Section(14, "1", "FA", 26, 35,
                    35, 1);

            // Create command history (Invoker)
            CommandHistory history = new CommandHistory();
            // Test 1: Register for courses
            System.out.println("--- Test 1: Course Registration ---");
            // Create a register object (ConcreteCommand)
            BaseCommand register1 = new RegisterCommand(alice, cs101);
            // Invoker executes the command composed in it by Week5Test (Client).
            history.executeCommand(register1);

            BaseCommand register2 = new RegisterCommand(alice, cs301);
            history.executeCommand(register2);

            // Test 2: Try to register for full course (should fail)
            System.out.println("\n--- Test 2: Register for Full Course ---");
            BaseCommand register3 = new RegisterCommand(alice, math301);
            history.executeCommand(register3);

            // Test 3: Add to waitlist
            System.out.println("\n--- Test 3: WaitlistEntry ---");
            BaseCommand waitlist = new WaitlistCommand(alice, math301);
            history.executeCommand(waitlist);

            // Test 4: Make payment
            System.out.println("\n--- Test 4: Payment ---");
            BaseCommand payment = new PaymentCommand(alice, 1500.00, "Credit Card");
            history.executeCommand(payment);

            // Test 5: Update contact info
            System.out.println("\n--- Test 5: Update Contact Info ---");
            BaseCommand updateContact = new UpdateContactCommand(alice,
                    "alice.j@college.edu", "555-1234");
            history.executeCommand(updateContact);

            // Show command history
            System.out.println("\n" + "=".repeat(50));
            history.showHistory();

            // Test 6: Undo operations
            System.out.println("\n--- Test 6: Undo Operations ---");
            history.undo(); // Undo contact update
            history.undo(); // Undo payment
            history.undo(); // Undo waitlist

            // Test 7: Redo operations
            System.out.println("\n--- Test 7: Redo Operations ---");
            history.redo(); // Redo waitlist
            history.redo(); // Redo payment

            // Test 8: Drop a course
            System.out.println("\n--- Test 8: Drop Course ---");
            BaseCommand drop = new DropCommand(alice, cs101);
            history.executeCommand(drop);

            // Test 9: Undo drop (re-enroll)
            System.out.println("\n--- Test 9: Undo Drop ---");
            history.undo();

            // Test 10: Macro command (register for multiple courses)
            System.out.println("\n--- Test 10: Macro Command ---");
            MacroCommand registerMultiple = new MacroCommand("Register for 2 courses");

            Section eng101 = new Section(4, "ENG101", "English Composition", "001",
                    "Fall 2024", 25, 0, 3);
            Section hist201 = new Section(5, "HIST201", "World History", "001",
                    "Fall 2024", 30, 0, 4);

            registerMultiple.addCommand(new RegisterCommand(alice, eng101));
            registerMultiple.addCommand(new RegisterCommand(alice, hist201));

            history.executeCommand(registerMultiple);

            // Final history
            System.out.println("\n" + "=".repeat(50));
            history.showHistory();

            // Show Alice's notifications
            System.out.println("\n" + "=".repeat(50));
            alice.viewNotifications();

            // Test 11: Clear history
            System.out.println("\n--- Test 11: Clear History ---");
            history.clear();
            history.showHistory();

        } catch (SQLException | IllegalAccessException e) {
            e.printStackTrace();
        }


        dbManager.shutdown();
    }
}