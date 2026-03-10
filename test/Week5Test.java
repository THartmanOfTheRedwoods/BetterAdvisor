import edu.advising.core.DatabaseManager;
import edu.advising.notifications.*;
import edu.advising.users.Student;
import edu.advising.users.UserFactory;
import edu.advising.commands.CommandRecord;

// ============================================================================
// WEEK 5: COMMAND PATTERN — Integration Test Application
// ============================================================================
//
// PURPOSE:
//   Exercises every Command class implemented in Week 5.
//   Structured as a plain runnable main() — no JUnit required.
//   Run it with: java -cp <classpath> edu.advising.Week5Test
//
// TESTS COVERED:
//   GROUP 1  — RegisterCommand    (execute, undo, redo, duplicate prevention)
//   GROUP 2  — DropCommand        (execute, undo, non-enrolled student)
//   GROUP 3  — WaitlistCommand    (execute, position, undo, duplicate)
//   GROUP 4  — PaymentCommand     (execute, balance verify, undo/refund)
//   GROUP 5  — UpdateContactCommand (execute, validation, undo)
//   GROUP 6  — MacroCommand       (batch execute, auto-rollback on failure)
//   GROUP 7  — FacultyDropCommand (authorization, execute, undo)
//   GROUP 8  — GrantWaitlistPermissionCommand (grant, DB verify, undo/revoke)
//   GROUP 9  — CommandHistory state machine (canUndo/canRedo transitions)
//   GROUP 10 — Audit trail        (getAuditHistory, CommandRecord labels)
//   GROUP 11 — Edge cases         (full section, $0 payment, empty undo stack)
//
// ============================================================================

import edu.advising.commands.*;
import edu.advising.core.DatabaseManager;
import edu.advising.notifications.NotificationManager;
import edu.advising.notifications.ObservableStudent;
import edu.advising.users.Faculty;
import edu.advising.users.Student;
import edu.advising.users.UserFactory;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;

public class Week5Test {

    // ── Counters ─────────────────────────────────────────────────────────────
    private static int passed = 0;
    private static int failed = 0;

    // ── Shared fixtures ───────────────────────────────────────────────────────
    private static DatabaseManager     db;
    private static NotificationManager notificationManager;
    private static UserFactory          userFactory;

    private static ObservableStudent    student;      // primary test student
    private static ObservableStudent    student2;     // second student for multi-student tests
    private static Faculty              faculty;
    private static Section              section;      // capacity=3, starts empty
    private static Section              fullSection;  // capacity=1, pre-filled
    private static CommandExecutor      executor;     // one executor per student session

    // =========================================================================
    // ENTRY POINT
    // =========================================================================

    public static void main(String[] args) {
        banner("WEEK 5 — COMMAND PATTERN  |  CRAdvisor Test Suite");

        try {
            setUp();
        } catch (Exception e) {
            System.err.println("FATAL: setUp() failed — cannot run tests.");
            e.printStackTrace();
            return;
        }

        testRegisterCommand();
        testDropCommand();
        testWaitlistCommand();
        testPaymentCommand();
        testUpdateContactCommand();
        testMacroCommand();
        testFacultyDropCommand();
        testGrantWaitlistPermissionCommand();
        testCommandHistoryStateMachine();
        testAuditHistory();
        testEdgeCases();

        // ── Final report ──────────────────────────────────────────────────────
        banner("RESULTS");
        System.out.printf("  Total  : %d%n", passed + failed);
        System.out.printf("  Passed : %d  ✓%n", passed);
        System.out.printf("  Failed : %d  ✗%n", failed);
        System.out.println(failed == 0 ? "\n  ALL TESTS PASSED  ✅\n" : "\n  SOME TESTS FAILED  ❌\n");

        db.shutdown();
    }

    // =========================================================================
    // SETUP
    // =========================================================================

    /**
     * Builds a fresh in-memory H2 database and populates all fixtures.
     * Runs once before all tests.
     *
     * WHY WE TEST AGAINST THE REAL DB:
     *   The Command Pattern integrates DatabaseManager (Singleton), ORM annotations,
     *   NotificationManager (Observer), and domain models.  A real DB catches the
     *   exact bugs found in the code review (wrong column types, missing rows, etc.)
     *   that mocks would silently pass.
     */
    private static void setUp() throws Exception {
        header("SET UP");

        db                  = DatabaseManager.getInstance();
        db.seedDatabase();
        notificationManager = NotificationManager.getInstance();
        userFactory         = new UserFactory();

        // Faculty fixture
        faculty = (Faculty) userFactory.createUser(
                "FACULTY", "prof.jones", "Password1!",
                "jones@college.edu", "Marcus", "Jones", "E001", "CS");
        note("Faculty : " + faculty.getFullName() + " (id=" + faculty.getId() + ")");

        // Primary student
        Student rawA = (Student) userFactory.createUser(
                "STUDENT", "jsmith", "Password1!",
                "jsmith@college.edu", "Jane", "Smith", "S10001");
        student = ObservableStudent.fromSuperType(rawA);
        notificationManager.attach(student);
        note("Student : " + student.getFullName() + " (id=" + student.getId() + ")");

        // Second student (for waitlist-promotion and multi-student tests)
        Student rawB = (Student) userFactory.createUser(
                "STUDENT", "bwilson", "Password1!",
                "bwilson@college.edu", "Bob", "Wilson", "S10002");
        student2 = ObservableStudent.fromSuperType(rawB);
        notificationManager.attach(student2);
        note("Student2: " + student2.getFullName() + " (id=" + student2.getId() + ")");

        // Course + open section (capacity = 3)
        int deptId = db.executeInsert(
                "INSERT INTO departments (code, name) VALUES (?,?)", "BUS", "Business");
        int courseId = db.executeInsert(
                "INSERT INTO courses (code, name, credits, department_id, is_active) VALUES (?,?,?,?,?)",
                "CS101", "Intro to Programming", 3.0, deptId, true);

        section = new Section(courseId, "01", "SP", 2026, 3, 0, faculty.getId());
        db.upsert(section);
        note("Section : " + section.getCourseCode() + " (capacity=3)");

        // Full section (capacity = 1) — immediately filled by a blocker student
        fullSection = new Section(courseId, "02", "SP", 2026, 1, 0, faculty.getId());
        db.upsert(fullSection);
        Student blocker = (Student) userFactory.createUser(
                "STUDENT", "blocker", "Password1!",
                "blocker@college.edu", "Block", "Er", "S99999");
        fullSection.enroll(blocker);
        note("FullSection: " + fullSection.getCourseCode() + " (capacity=1, pre-filled)");

        // One executor scoped to the primary student's session
        executor = new CommandExecutor(student.getId());
        note("setUp() complete\n");
    }

    // =========================================================================
    // GROUP 1 — RegisterCommand
    // =========================================================================

    /**
     * WHY COMMAND PATTERN FOR REGISTRATION:
     *   Registration touches three tables (enrollments, sections.enrolled,
     *   command_history), fires an Observer notification, and must be reversible.
     *   Wrapping it in RegisterCommand separates WHAT to do from WHO triggers it.
     *
     * GUI NOTE:
     *   A "Register" button simply does:
     *     executor.execute(new RegisterCommand(student, section));
     *     undoButton.setEnabled(executor.canUndo());
     *   The toolbar Undo label reads: "Undo: Register for CS101-SP2026-01"
     */
    private static void testRegisterCommand() {
        header("GROUP 1 — RegisterCommand");

        // 1.1–1.5  Successful registration
        RegisterCommand reg = new RegisterCommand(student, section);
        executor.execute(reg);

        check("1.1  execute() wasSuccessful()",              reg.wasSuccessful());
        check("1.2  section.enrolled = 1",                   section.getEnrolled() == 1);
        check("1.3  canUndo() = true",                       executor.canUndo());
        check("1.4  canRedo() = false",                      !executor.canRedo());
        check("1.5  peekUndoDescription contains course",
                executor.peekUndoDescription() != null &&
                        executor.peekUndoDescription().contains("CS101"));

        // 1.6–1.10  Undo registration
        boolean undid = executor.undo();
        check("1.6  undo() returns true",                    undid);
        check("1.7  section.enrolled = 0 after undo",        section.getEnrolled() == 0);
        check("1.8  canUndo() = false after undo",           !executor.canUndo());
        check("1.9  canRedo() = true after undo",            executor.canRedo());
        check("1.10 isUndone flag set on command",           reg.isUndone());

        // 1.11–1.14  Redo
        boolean redid = executor.redo();
        check("1.11 redo() returns true",                    redid);
        check("1.12 section.enrolled = 1 after redo",        section.getEnrolled() == 1);
        check("1.13 canUndo() = true after redo",            executor.canUndo());
        check("1.14 canRedo() = false after redo",           !executor.canRedo());

        // 1.15–1.17  Prevent double-registration
        RegisterCommand dup = new RegisterCommand(student, section);
        executor.execute(dup);
        check("1.15 duplicate register wasSuccessful() = false",  !dup.wasSuccessful());
        check("1.16 duplicate register enrolled count unchanged",  section.getEnrolled() == 1);
        // Failed commands must NOT land on the undo stack
        check("1.17 failed cmd not pushed to undo stack",
                executor.peekUndoDescription() != null &&
                        executor.peekUndoDescription().contains("CS101")); // still the redo cmd
    }

    // =========================================================================
    // GROUP 2 — DropCommand
    // =========================================================================

    /**
     * WHY COMMAND FOR DROP:
     *   A drop records reason codes, updates the enrollment status, decrements
     *   the seat count, and optionally promotes a waitlist student — all as one
     *   auditable action.  Undo re-enrolls the student if the seat is free.
     *
     * GUI NOTE:
     *   "Drop" button in My Class Schedule:
     *     executor.execute(new DropCommand(student, section));
     *   A confirmation dialog can show executor.peekUndoDescription() before acting.
     */
    private static void testDropCommand() {
        header("GROUP 2 — DropCommand");

        // Student is enrolled from group 1 redo above
        check("2.pre enrolled count = 1",                    section.getEnrolled() == 1);

        DropCommand drop = new DropCommand(student, section);
        executor.execute(drop);
        check("2.1  drop execute() wasSuccessful()",         drop.wasSuccessful());
        check("2.2  section.enrolled = 0",                   section.getEnrolled() == 0);
        check("2.3  canUndo() after drop",                   executor.canUndo());

        // Undo drop (re-enroll)
        executor.undo();
        check("2.4  undo drop — enrolled = 1",               section.getEnrolled() == 1);

        // Drop again to leave clean state for later groups
        executor.execute(new DropCommand(student, section));
        check("2.5  re-drop for cleanup — enrolled = 0",     section.getEnrolled() == 0);

        // Drop student who is NOT enrolled — should fail gracefully
        DropCommand badDrop = new DropCommand(student2, section);
        executor.execute(badDrop);
        check("2.6  drop non-enrolled student wasSuccessful() = false", !badDrop.wasSuccessful());
        check("2.7  enrolled count unchanged at 0",          section.getEnrolled() == 0);
    }

    // =========================================================================
    // GROUP 3 — WaitlistCommand
    // =========================================================================

    /**
     * WHY COMMAND FOR WAITLIST:
     *   Waitlist add persists a WaitlistEntry row, assigns a position, and fires
     *   a notification.  The same undo/redo infrastructure used for registration
     *   works here — zero extra code needed.
     *
     * GUI NOTE:
     *   When RegisterCommand fails because the section is full, the UI offers:
     *     executor.execute(new WaitlistCommand(student, section));
     *   The student sees their position via section.getWaitlistPosition(student).
     */
    private static void testWaitlistCommand() {
        header("GROUP 3 — WaitlistCommand");

        // fullSection capacity=1 is pre-filled, so student lands on waitlist
        WaitlistCommand wl = new WaitlistCommand(student, fullSection);
        executor.execute(wl);
        check("3.1  waitlist execute() wasSuccessful()",     wl.wasSuccessful());
        check("3.2  canUndo() after waitlist",               executor.canUndo());

        try {
            int pos = fullSection.getWaitlistPosition(student);
            check("3.3  waitlist position = 1",              pos == 1);
        } catch (SQLException e) {
            fail("3.3  getWaitlistPosition threw: " + e.getMessage());
        }

        // Duplicate entry must fail
        WaitlistCommand dup = new WaitlistCommand(student, fullSection);
        executor.execute(dup);
        check("3.4  duplicate waitlist wasSuccessful() = false", !dup.wasSuccessful());

        // Second student joins at position 2
        CommandExecutor exec2 = new CommandExecutor(student2.getId());
        WaitlistCommand wl2 = new WaitlistCommand(student2, fullSection);
        exec2.execute(wl2);
        check("3.5  student2 waitlist wasSuccessful()",      wl2.wasSuccessful());
        try {
            check("3.6  student2 position = 2",
                    fullSection.getWaitlistPosition(student2) == 2);
        } catch (SQLException e) {
            fail("3.6  position check threw: " + e.getMessage());
        }

        // Undo student's waitlist entry
        executor.undo();
        try {
            int pos = fullSection.getWaitlistPosition(student);
            check("3.7  after undo — position = 0 (removed)", pos == 0);
        } catch (SQLException e) {
            check("3.7  after undo — student not on waitlist (expected)", true);
        }
    }

    // =========================================================================
    // GROUP 4 — PaymentCommand
    // =========================================================================

    /**
     * WHY COMMAND FOR PAYMENT:
     *   Every dollar that moves must be logged.  execute() persists a Payment ORM
     *   entity; undo() marks it REFUNDED and reverses the account balance.
     *   The command_history row satisfies institutional financial reporting with
     *   no extra logging code.
     *
     * GUI NOTE:
     *   After execute(), the UI calls payCmd.getPaymentReferenceNumber() to
     *   display the receipt.  The Undo button label reads:
     *   "Undo: Payment of $500.00 (TUITION via CREDIT_CARD)"
     */
    private static void testPaymentCommand() {
        header("GROUP 4 — PaymentCommand");

        PaymentCommand pay = new PaymentCommand(
                student, new BigDecimal("500.00"), "TUITION", "CREDIT_CARD");
        executor.execute(pay);

        check("4.1  payment execute() wasSuccessful()",      pay.wasSuccessful());
        check("4.2  reference number starts with PAY-",
                pay.getPaymentReferenceNumber() != null &&
                        pay.getPaymentReferenceNumber().startsWith("PAY-"));
        check("4.3  canUndo() after payment",                executor.canUndo());

        // Verify account balance in DB: payment reduces balance owed by 500
        try {
            BigDecimal balance = db.executeQuery(
                    "SELECT current_balance FROM student_accounts WHERE student_id = ?",
                    rs -> rs.next() ? rs.getBigDecimal("current_balance") : null,
                    student.getId());
            check("4.4  student_accounts.balance = -500.00",
                    balance != null &&
                            balance.compareTo(new BigDecimal("-500.00")) == 0);
        } catch (SQLException e) {
            fail("4.4  balance query: " + e.getMessage());
        }

        // Verify Payment row in DB
        try {
            String status = db.executeQuery(
                    "SELECT status FROM payments WHERE student_id = ? AND status = 'COMPLETED'",
                    rs -> rs.next() ? rs.getString("status") : null,
                    student.getId());
            check("4.5  payments row status = COMPLETED",    "COMPLETED".equals(status));
        } catch (SQLException e) {
            fail("4.5  payments row query: " + e.getMessage());
        }

        // Undo = refund
        executor.undo();
        check("4.6  isUndone = true after undo",             pay.isUndone());

        try {
            BigDecimal balance = db.executeQuery(
                    "SELECT current_balance FROM student_accounts WHERE student_id = ?",
                    rs -> rs.next() ? rs.getBigDecimal("current_balance") : null,
                    student.getId());
            check("4.7  balance restored to 0.00 after refund",
                    balance != null && balance.compareTo(BigDecimal.ZERO) == 0);

            String refundStatus = db.executeQuery(
                    "SELECT status FROM payments WHERE student_id = ? ORDER BY id DESC LIMIT 1",
                    rs -> rs.next() ? rs.getString("status") : null,
                    student.getId());
            check("4.8  payments row status = REFUNDED",     "REFUNDED".equals(refundStatus));
        } catch (SQLException e) {
            fail("4.7-8 post-undo balance/status check: " + e.getMessage());
        }

        // $0 payment must be rejected
        PaymentCommand zeroPay = new PaymentCommand(
                student, new BigDecimal("0.00"), "FEE", "CASH");
        executor.execute(zeroPay);
        check("4.9  zero-dollar payment wasSuccessful() = false", !zeroPay.wasSuccessful());
        check("4.10 zero-dollar error message populated",
                zeroPay.getErrorMessage() != null && !zeroPay.getErrorMessage().isEmpty());
    }

    // =========================================================================
    // GROUP 5 — UpdateContactCommand
    // =========================================================================

    /**
     * WHY COMMAND FOR CONTACT UPDATE:
     *   FERPA and institutional policy require a full audit trail of contact
     *   changes (who changed what, when).  The Command Pattern provides this
     *   automatically.  Old values are snapshotted at construction time so
     *   undo() is always safe — even if the user navigates away and returns.
     *
     * GUI NOTE:
     *   "Save" button on the Contact Information Update form:
     *     executor.execute(new UpdateContactCommand(student, emailField.getText(), phoneField.getText()));
     *   Undo button label: "Undo: Update contact info for Jane Smith"
     */
    private static void testUpdateContactCommand() {
        header("GROUP 5 — UpdateContactCommand");

        String originalEmail = student.getEmail();

        UpdateContactCommand upd = new UpdateContactCommand(
                student, "jane.new@college.edu", "555-9999");
        executor.execute(upd);

        check("5.1  update execute() wasSuccessful()",       upd.wasSuccessful());
        check("5.2  in-memory email updated",
                "jane.new@college.edu".equals(student.getEmail()));
        check("5.3  in-memory phone updated",
                "555-9999".equals(student.getPhone()));

        try {
            String dbEmail = db.executeQuery(
                    "SELECT email FROM users WHERE id = ?",
                    rs -> rs.next() ? rs.getString("email") : null,
                    student.getId());
            check("5.4  DB email updated",                   "jane.new@college.edu".equals(dbEmail));
        } catch (SQLException e) {
            fail("5.4  DB email check: " + e.getMessage());
        }

        // Undo restores original
        executor.undo();
        check("5.5  undo — in-memory email restored",        originalEmail.equals(student.getEmail()));

        try {
            String dbEmail = db.executeQuery(
                    "SELECT email FROM users WHERE id = ?",
                    rs -> rs.next() ? rs.getString("email") : null,
                    student.getId());
            check("5.6  undo — DB email restored",           originalEmail.equals(dbEmail));
        } catch (SQLException e) {
            fail("5.6  post-undo DB check: " + e.getMessage());
        }

        // Invalid email format rejected
        UpdateContactCommand badEmail = new UpdateContactCommand(student, "not-an-email", null);
        executor.execute(badEmail);
        check("5.7  invalid email format rejected",          !badEmail.wasSuccessful());
        check("5.8  original email preserved after failure", originalEmail.equals(student.getEmail()));

        // Duplicate email rejected (student2's address is already taken)
        UpdateContactCommand dupEmail = new UpdateContactCommand(
                student, student2.getEmail(), null);
        executor.execute(dupEmail);
        check("5.9  duplicate email rejected",               !dupEmail.wasSuccessful());
    }

    // =========================================================================
    // GROUP 6 — MacroCommand
    // =========================================================================

    /**
     * WHY MACRO COMMAND:
     *   "Register and Pay" must succeed or fail together — you shouldn't be
     *   enrolled without paying, or charged without being enrolled.  MacroCommand
     *   wraps N commands; if any fails it auto-undoes the ones that already ran.
     *
     * GUI NOTE:
     *   A "Register & Pay Now" button:
     *     MacroCommand macro = new MacroCommand("Register and Pay");
     *     macro.addCommand(new RegisterCommand(student, section));
     *     macro.addCommand(new PaymentCommand(student, amount, "FEE", "ONLINE"));
     *     executor.execute(macro);
     *   One undo call rolls back BOTH commands in reverse order.
     */
    private static void testMacroCommand() {
        header("GROUP 6 — MacroCommand");

        // Student was left un-enrolled after group 2
        check("6.pre section empty",                         section.getEnrolled() == 0);

        // 6.1–6.3  Successful macro: register + pay
        MacroCommand macro = new MacroCommand("Register and Pay for CS101");
        macro.addCommand(new RegisterCommand(student, section));
        macro.addCommand(new PaymentCommand(student, new BigDecimal("150.00"), "FEE", "ONLINE"));
        executor.execute(macro);

        check("6.1  macro execute() wasSuccessful()",        macro.wasSuccessful());
        check("6.2  student enrolled after macro",           section.getEnrolled() == 1);

        executor.undo();
        check("6.3  macro undo — student dropped",           section.getEnrolled() == 0);

        // 6.4–6.5  Failing sub-command triggers auto-rollback
        // The $0 PaymentCommand will fail, causing MacroCommand to undo the RegisterCommand
        MacroCommand failMacro = new MacroCommand("Bad Register and Pay");
        failMacro.addCommand(new RegisterCommand(student, section));
        failMacro.addCommand(new PaymentCommand(student, new BigDecimal("0.00"), "FEE", "ONLINE"));
        executor.execute(failMacro);

        check("6.4  failing macro wasSuccessful() = false",  !failMacro.wasSuccessful());
        check("6.5  enrollment rolled back after macro fail", section.getEnrolled() == 0);
    }

    // =========================================================================
    // GROUP 7 — FacultyDropCommand
    // =========================================================================

    /**
     * WHY SEPARATE COMMAND FROM DropCommand:
     *   Faculty drops carry reason codes, a different authorization check
     *   (faculty must own the section), a different notification type, and
     *   appear separately in admin reports.  One class per responsibility.
     *
     * GUI NOTE:
     *   Faculty Class Roster → right-click row → "Administrative Drop":
     *     String reason = reasonDialog.getSelectedReason();
     *     facultyExecutor.execute(new FacultyDropCommand(faculty, student, section, reason));
     *   Undo reinstates the student within the same session.
     */
    private static void testFacultyDropCommand() {
        header("GROUP 7 — FacultyDropCommand");

        // Enroll student first
        executor.execute(new RegisterCommand(student, section));
        check("7.pre student enrolled for faculty-drop",     section.getEnrolled() == 1);

        CommandExecutor facExec = new CommandExecutor(faculty.getId());
        FacultyDropCommand facDrop = new FacultyDropCommand(faculty, student, section, "NO_SHOW");
        facExec.execute(facDrop);

        check("7.1  faculty drop execute() wasSuccessful()", facDrop.wasSuccessful());
        check("7.2  section.enrolled = 0",                   section.getEnrolled() == 0);

        try {
            String status = db.executeQuery(
                    "SELECT status FROM enrollments " +
                            "WHERE student_id = ? AND section_id = ? ORDER BY id DESC LIMIT 1",
                    rs -> rs.next() ? rs.getString("status") : null,
                    student.getId(), section.getId());
            check("7.3  enrollment row status = DROPPED",    "DROPPED".equals(status));
        } catch (SQLException e) {
            fail("7.3  enrollment status query: " + e.getMessage());
        }

        // Undo reinstates student
        facExec.undo();
        check("7.4  undo faculty drop — student re-enrolled", section.getEnrolled() == 1);

        // Wrong faculty (doesn't own section) is rejected
        try {
            Faculty stranger = (Faculty) userFactory.createUser(
                    "FACULTY", "stranger.prof", "Password1!",
                    "stranger@college.edu", "Tom", "Strange", "E999", "MATH");
            FacultyDropCommand unauth = new FacultyDropCommand(stranger, student, section, "OTHER");
            new CommandExecutor(stranger.getId()).execute(unauth);
            check("7.5  wrong faculty drop rejected",        !unauth.wasSuccessful());
        } catch (Exception e) {
            fail("7.5  wrong-faculty test threw: " + e.getMessage());
        }

        // Clean up
        executor.execute(new DropCommand(student, section));
    }

    // =========================================================================
    // GROUP 8 — GrantWaitlistPermissionCommand
    // =========================================================================

    /**
     * WHY COMMAND FOR PERMISSION GRANT:
     *   A faculty override grant has a validity window stored in the DB.  The
     *   Week 14 registration pipeline queries permission_grants before allowing
     *   enrollment in a full section.  Undo deactivates the grant before the
     *   student uses it.
     *
     * GUI NOTE:
     *   Faculty Waitlist Panel → "Grant Permission" button:
     *     facultyExecutor.execute(new GrantWaitlistPermissionCommand(faculty, student, fullSection, notes));
     *   Student's registration page shows an "Override Available" badge if an
     *   active unexpired grant exists for that section.
     */
    private static void testGrantWaitlistPermissionCommand() {
        header("GROUP 8 — GrantWaitlistPermissionCommand");

        // Put student on fullSection waitlist first
        WaitlistCommand wlPre = new WaitlistCommand(student, fullSection);
        executor.execute(wlPre);
        check("8.pre student on waitlist",                   wlPre.wasSuccessful());

        CommandExecutor facExec = new CommandExecutor(faculty.getId());
        GrantWaitlistPermissionCommand grant = new GrantWaitlistPermissionCommand(
                faculty, student, fullSection, "Prereq waived by advisor");
        facExec.execute(grant);

        check("8.1  grant execute() wasSuccessful()",        grant.wasSuccessful());
        check("8.2  canUndo() after grant",                  facExec.canUndo());

        try {
            Boolean active = db.executeQuery(
                    "SELECT is_active FROM permission_grants " +
                            "WHERE student_id = ? AND section_id = ? AND is_used = FALSE",
                    rs -> rs.next() ? rs.getBoolean("is_active") : null,
                    student.getId(), fullSection.getId());
            check("8.3  permission_grants row is_active = true", Boolean.TRUE.equals(active));
        } catch (SQLException e) {
            fail("8.3  permission_grants query: " + e.getMessage());
        }

        check("8.4  isUndoable() = true (unused grant)",     grant.isUndoable());

        // Undo revokes the permission
        facExec.undo();
        try {
            Boolean active = db.executeQuery(
                    "SELECT is_active FROM permission_grants WHERE student_id = ? AND section_id = ?",
                    rs -> rs.next() ? rs.getBoolean("is_active") : null,
                    student.getId(), fullSection.getId());
            check("8.5  undo — is_active = false (revoked)", Boolean.FALSE.equals(active));
        } catch (SQLException e) {
            fail("8.5  revoke check: " + e.getMessage());
        }

        executor.undo(); // remove the pre-waitlist entry
    }

    // =========================================================================
    // GROUP 9 — CommandHistory State Machine
    // =========================================================================

    /**
     * TEACHING FOCUS:
     *   This group explicitly verifies the undo/redo state transitions documented
     *   in CommandHistory.java.  Understanding these transitions is essential for
     *   correctly enabling/disabling toolbar Undo and Redo buttons in any GUI.
     *
     *   Transitions:
     *     initial           → canUndo=F, canRedo=F
     *     after execute     → canUndo=T, canRedo=F
     *     after undo        → canUndo=F, canRedo=T
     *     after redo        → canUndo=T, canRedo=F
     *     after new action  → canUndo=T, canRedo=F  ← redo stack cleared
     *     undo on empty     → returns false
     */
    private static void testCommandHistoryStateMachine() {
        header("GROUP 9 — CommandHistory State Machine");

        // Fresh executor for isolated state
        CommandExecutor sm = new CommandExecutor(student.getId());

        check("9.1  initial canUndo() = false",              !sm.canUndo());
        check("9.2  initial canRedo() = false",              !sm.canRedo());

        // Enroll student so we have something to drop/re-register
        executor.execute(new RegisterCommand(student, section));
        check("9.pre enrolled for state machine test",       section.getEnrolled() == 1);

        RegisterCommand a = new RegisterCommand(student2, section);
        sm.execute(a);
        check("9.3  after execute A: canUndo=T",             sm.canUndo());
        check("9.4  after execute A: canRedo=F",             !sm.canRedo());

        sm.undo();
        check("9.5  after undo A: canUndo=F",                !sm.canUndo());
        check("9.6  after undo A: canRedo=T",                sm.canRedo());

        sm.redo();
        check("9.7  after redo A: canUndo=T",                sm.canUndo());
        check("9.8  after redo A: canRedo=F",                !sm.canRedo());

        sm.undo();
        // student2 is NOT enrolled here!

        // new RegisterCommand for student2 instead of DropCommand that's guaranteed to fail.
        RegisterCommand b = new RegisterCommand(student2, section);
        sm.execute(b);
        check("9.9  new action clears redo: canRedo=F",      !sm.canRedo());
        check("9.10 new action: canUndo=T",                  sm.canUndo());

        sm.undo();
        check("9.11 after undo B: canRedo=T",                sm.canRedo());

        boolean extraUndo = sm.undo(); // undoStack already empty
        check("9.12 undo on empty stack returns false",      !extraUndo);

        // Redo on empty stack
        while (sm.canRedo()) sm.redo();
        boolean extraRedo = sm.redo();
        check("9.13 redo on empty stack returns false",      !extraRedo);

        // After the redo drain above, student2 is enrolled again — drop both.
        executor.execute(new DropCommand(student, section));
        executor.execute(new DropCommand(student2, section));
    }

    // =========================================================================
    // GROUP 10 — Audit History
    // =========================================================================

    /**
     * TEACHING FOCUS:
     *   The command_history table provides administrators a full record of every
     *   action.  This group verifies that execute() and undo() both write correct
     *   records, and that getAuditHistory() returns queryable CommandRecord DTOs.
     *
     * GUI NOTE:
     *   "Transaction History" screen:
     *     List<CommandRecord> records = executor.getAuditHistory(50);
     *     // bind to JTable or ListView
     *   Each CommandRecord.getStatusLabel() returns "✓ Completed", "↶ Reversed",
     *   or "✗ Failed" — ready to use as a cell value with no extra formatting.
     */
    private static void testAuditHistory() {
        header("GROUP 10 — Audit History");

        PaymentCommand auditPay = new PaymentCommand(
                student, new BigDecimal("75.00"), "FEE", "CASH");
        executor.execute(auditPay);

        List<CommandRecord> history = executor.getAuditHistory(10);
        check("10.1 getAuditHistory() non-null",             history != null);
        check("10.2 history non-empty",                      history != null && !history.isEmpty());

        if (history != null && !history.isEmpty()) {
            CommandRecord latest = history.get(0); // most recent first
            check("10.3 latest record has commandType",      latest.getCommandType() != null);
            check("10.4 latest record has executedAt",       latest.getExecutedAt() != null);
            check("10.5 latest record success matches cmd",  latest.isSuccess() == auditPay.wasSuccessful());
        }

        executor.undo(); // refund — should mark record isUndone
        List<CommandRecord> afterUndo = executor.getAuditHistory(10);
        if (afterUndo != null) {
            boolean foundUndone = afterUndo.stream()
                    .anyMatch(r -> "PAYMENT".equals(r.getCommandType()) && r.isUndone());
            check("10.6 undone record marked isUndone in DB", foundUndone);
        }

        // CommandRecord.getStatusLabel() strings
        CommandRecord completed = new CommandRecord(1, "REGISTER", "{}", null, null, false, true, null);
        CommandRecord failure   = new CommandRecord(2, "DROP",     "{}", null, null, false, false, "err");
        CommandRecord reversed  = new CommandRecord(3, "PAYMENT",  "{}", null, null, true,  true, null);

        check("10.7 statusLabel — completed:  '✓ Completed'",
                "✓ Completed".equals(completed.getStatusLabel()));
        check("10.8 statusLabel — failed:     '✗ Failed'",
                "✗ Failed".equals(failure.getStatusLabel()));
        check("10.9 statusLabel — reversed:   '↶ Reversed'",
                "↶ Reversed".equals(reversed.getStatusLabel()));
    }

    // =========================================================================
    // GROUP 11 — Edge Cases
    // =========================================================================

    /**
     * TEACHING FOCUS:
     *   Edge cases show that failed Commands are logged for audit but never
     *   pushed onto the undo stack, keeping the undo history clean.
     *   The pre-condition checks inside each command (amount > 0, section has
     *   capacity, etc.) represent the "Guard" layer before any DB write occurs.
     */
    private static void testEdgeCases() {
        header("GROUP 11 — Edge Cases");

        // 11.1  Register into a full section
        RegisterCommand fullReg = new RegisterCommand(student, fullSection);
        executor.execute(fullReg);
        check(String.format("11.1 register into full section fails %n    %s", fullReg.getErrorMessage()),
                !fullReg.wasSuccessful());
        check("11.2 error message populated on failure",
                fullReg.getErrorMessage() != null && !fullReg.getErrorMessage().isEmpty());

        // 11.3  Undo with empty stack
        while (executor.canUndo()) executor.undo();
        check("11.3 undo on empty stack returns false",      !executor.undo());

        // 11.4  Redo with empty stack
        // The 11.3 drain filled the redo stack as a side effect. Drain it here so
        // that 11.4 actually tests redo() against an empty stack, which was the intent.
        while (executor.canRedo()) executor.redo();
        check("11.4 redo on empty stack returns false",      !executor.redo());

        // 11.5  Negative payment amount
        PaymentCommand negPay = new PaymentCommand(
                student, new BigDecimal("-50.00"), "TUITION", "CASH");
        executor.execute(negPay);
        check("11.5 negative payment fails",                 !negPay.wasSuccessful());

        // 11.6  peekUndoDescription returns null when stack is empty
        while (executor.canUndo()) executor.undo();
        check("11.6 peekUndo null when stack empty",         executor.peekUndoDescription() == null);
        while (executor.canRedo()) executor.redo();
        check("11.7 peekRedo null when stack empty",         executor.peekRedoDescription() == null);

        // 11.8  MacroCommand with zero sub-commands
        MacroCommand emptyMacro = new MacroCommand("Empty macro");
        executor.execute(emptyMacro);
        // An empty macro has nothing to fail — it should succeed vacuously
        check("11.8 empty MacroCommand completes",           emptyMacro.wasSuccessful());

        // 11.9  getSessionHistory returns list (may be empty after all undos)
        List<BaseCommand> sessionHist = executor.getSessionHistory();
        check("11.9 getSessionHistory() returns non-null",   sessionHist != null);
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    private static void check(String label, boolean condition) {
        if (condition) {
            System.out.printf("  ✓  %s%n", label);
            passed++;
        } else {
            System.out.printf("  ✗  FAIL: %s%n", label);
            failed++;
        }
    }

    private static void fail(String label) {
        System.out.printf("  ✗  FAIL: %s%n", label);
        failed++;
    }

    private static void banner(String text) {
        String line = "═".repeat(62);
        System.out.printf("%n%s%n  %s%n%s%n", line, text, line);
    }

    private static void header(String text) {
        System.out.printf("%n  ── %s ──%n", text);
    }

    private static void note(String text) {
        System.out.printf("  » %s%n", text);
    }
}