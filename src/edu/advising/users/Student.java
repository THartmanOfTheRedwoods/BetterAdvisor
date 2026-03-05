package edu.advising.users;

import edu.advising.commands.Section;
import edu.advising.commands.WaitlistEntry;
import edu.advising.core.*;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;

/**
 * ADD ANNOTATION STUFF ON Command Pattern Week
 * -
 * Student - Concrete user type
 */
@Table(name = "students", isSubTable = true)
public class Student extends User {
    @Id
    @Column(name = "student_id")
    protected String studentId;
    @Column(name = "gpa")
    protected BigDecimal gpa;
    @Column(name = "enrollment_status")
    protected String enrollmentStatus;
    @Column(name = "academic_standing")
    protected String academicStanding;
    @Column(name = "classification")
    protected String classification;
    @Column(name = "major")
    protected String major;
    @Column(name = "minor")
    protected String minor;
    @Column(name = "advisor_id")
    protected int advisorId;
    @ManyToMany(
            targetEntity = Section.class,
            joinTable = "enrollments",
            joinColumn = "student_id", // Linking table's FK for Student & User table's PK
            inverseJoinColumn = "section_id" // Linking table's FK for Section table's PK
    )
    private List<Section> sections;
    @OneToMany(targetEntity = WaitlistEntry.class, mappedBy = "student_id")
    private List<WaitlistEntry> waitlist;

    public Student() {}

    public Student(String username, String password, String email,
                   String firstName, String lastName, String studentId) {
        super(username, password, email, firstName, lastName);
        this.userType = "STUDENT";
        this.studentId = studentId;
        this.gpa = new BigDecimal("0.0");
    }

    @Override
    public void showDashboard() {
        System.out.println("\n=== STUDENT DASHBOARD ===");
        System.out.println("Student ID: " + studentId);
        System.out.println("Name: " + firstName + " " + lastName);
        System.out.println("GPA: " + gpa.toPlainString());
        System.out.println("\nAvailable Features:");
        System.out.println("- Register for Classes");
        System.out.println("- View Schedule");
        System.out.println("- Check Grades");
        System.out.println("- Financial Aid");
        System.out.println("- Make Payment");
    }

    protected void ensureId() throws SQLException, IllegalAccessException {
        if(this.getId() == 0) {
            // If the id is not set, we need to save this object to get an id to set on the list items.
            DatabaseManager.getInstance().upsert(this);
        }
    }

    // Getters and setters
    public String getStudentId() {
        return studentId;
    }

    public BigDecimal getGpa() {
        return gpa;
    }

    public void setGpa(BigDecimal gpa) {
        this.gpa = gpa;
    }

    public String getEnrollmentStatus() {
        return enrollmentStatus;
    }

    public void setEnrollmentStatus(String enrollmentStatus) {
        this.enrollmentStatus = enrollmentStatus;
    }

    public String getAcademicStanding() {
        return academicStanding;
    }

    public void setAcademicStanding(String academicStanding) {
        this.academicStanding = academicStanding;
    }

    public String getClassification() {
        return classification;
    }

    public void setClassification(String classification) {
        this.classification = classification;
    }

    public String getMajor() {
        return major;
    }

    public void setMajor(String major) {
        this.major = major;
    }

    public String getMinor() {
        return minor;
    }

    public void setMinor(String minor) {
        this.minor = minor;
    }

    public int getAdvisorId() {
        return advisorId;
    }

    public void setAdvisorId(int advisorId) {
        this.advisorId = advisorId;
    }

    public List<Section> getSections() throws SQLException {
        if (this.sections == null) {
            this.sections = DatabaseManager.getInstance().fetchManyToMany(
                    Section.class, "enrollments",
                    "student_id", // Linking table's FK for Student & User table's PK
                    "section_id", // Linking table's FK for Section table's PK
                    this.id
            );
        }
        return this.sections;
    }

    public void setSections(List<Section> sections) {
        this.sections = sections;
    }

    public List<WaitlistEntry> getWaitlist() throws SQLException {
        // TODO: Gotta find a way to modify the fetch calls to take additional filters since this will return
        //   WaitlistEntries of ANY age and in ANY status.
        if (this.waitlist == null) {
            // Lazy Load: Use the generic fetchMany from DatabaseManager
            this.waitlist = DatabaseManager.getInstance()
                    .fetchMany(WaitlistEntry.class, "student_id", this.id);
        }
        return this.waitlist;
    }

    public void setWaitlist(List<WaitlistEntry> waitlist) throws SQLException, IllegalAccessException {
        ensureId();
        // Now, let's add this object's id to the related list items foreign key id
        for(WaitlistEntry we : waitlist) { we.setStudentId(this.getId()); }
        // Now let's upsertAll of these list items (i.e. a batch) and set as this object's related field.
        DatabaseManager.getInstance().upsertAll(waitlist);
        this.waitlist = waitlist;
    }
}
