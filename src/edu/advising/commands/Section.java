package edu.advising.commands;

import edu.advising.core.*;
import edu.advising.users.Faculty;
import edu.advising.users.Student;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Course Section - Represents a course section
 */
@Table(name = "sections")
public class Section {
    @Id(isPrimary = true)
    @Column(name = "id", upsertIgnore = true)
    private int id;
    @Id
    @Column(name = "course_id", foreignKey = true)
    private int courseId;  // References courses
    @Id
    @Column(name = "section_number")
    private String sectionNumber;
    @Id
    @Column(name = "semester")
    private String semester;
    @Id
    @Column(name = "year")
    private int year;
    @Column(name = "capacity")
    private int capacity;
    @Column(name = "enrolled")
    private int enrolled;
    @Column(name = "faculty_id", nullableforeignKey = true)
    private int facultyId; // References faculty
    @Column(name = "room")
    private String room;
    @Column(name = "status")
    private String status;  //OPEN, CLOSED, CANCELLED
    @ManyToOne(targetEntity = Course.class, joinColumn = "course_id")
    private Course course; // Cached object representing this sections courses.
    @ManyToOne(targetEntity = Faculty.class, joinColumn = "faculty_id")
    private Faculty faculty; // Cached object representing this faculty that teaches this course.
    @ManyToMany(
            targetEntity = Student.class,
            joinTable = "enrollments",
            joinColumn = "section_id",
            inverseJoinColumn = "student_id"
    )
    private List<Student> enrolledStudents;
    @OneToMany(targetEntity = Enrollment.class, mappedBy = "section_id")
    private List<Enrollment> enrollments;
    @OneToMany(targetEntity = WaitlistEntry.class, mappedBy = "section_id")
    private List<WaitlistEntry> waitlist;

    public Section() {}

    public Section(int id, int courseId, String sectionNumber,
                   String semester, int year, int capacity, int enrolled, int facultyId, String room, String status) {
        this(id, courseId, sectionNumber, semester, year, capacity, enrolled, facultyId);
        this.room = room;
        this.status = status;
    }

    public Section(int id, int courseId, String sectionNumber,
                   String semester, int year, int capacity, int enrolled, int facultyId) {
        this(courseId, sectionNumber, semester, year, capacity, enrolled, facultyId);
        this.id = id;
    }

    public Section(int courseId, String sectionNumber,
                   String semester, int year, int capacity, int enrolled, int facultyId) {
        this(courseId, sectionNumber, semester, year, capacity);
        this.enrolled = enrolled;
        this.facultyId = facultyId;
    }

    public Section(int courseId, String sectionNumber, String semester, int year, int capacity) {
        this(sectionNumber, semester, year, capacity);
        this.courseId = courseId;
    }

    public Section(String sectionNumber, String semester, int year, int capacity) {
        this.sectionNumber = sectionNumber;
        this.semester = semester;
        this.year = year;
        this.capacity = capacity;
        this.enrolledStudents = new ArrayList<>();
        this.waitlist = new ArrayList<>();
    }

    public boolean hasCapacity() {
        return enrolled < capacity;
    }

    private boolean isAlreadyOnWaitlist(Student newStudent) {
        try {
            return getWaitlist().stream().anyMatch(we -> we.getStudentId() == newStudent.getId());
        } catch (SQLException se) {
            se.printStackTrace();
            return true;
        }
    }

    private boolean isAlreadyEnrolled(Student newStudent) {
        try {
            return getEnrolledStudents().stream().anyMatch(student -> student.getId() == newStudent.getId());
        } catch (SQLException se) {
            se.printStackTrace();
            return true;
        }
    }

    public int enroll(Student newStudent) {
        if (hasCapacity() && !isAlreadyEnrolled(newStudent)) {
            // TODO: Update DatabaseManager to handle generic composite object dependency updates.
            try {
                ensureId();
                Enrollment enrollment = new Enrollment(newStudent.getId(), this.getId());
                DatabaseManager.getInstance().upsert(enrollment);
                this.enrollments.add(enrollment);
                enrolledStudents.add(newStudent);
                enrolled++;
                // To make sure enrollment numbers get updated, could also make this a trigger in the database.
                DatabaseManager.getInstance().upsert(this);
                return enrollment.getId();
            } catch (SQLException | IllegalAccessException e) {
                return 0;
            }
        }
        return 0;
    }

    public boolean drop(Student dropStudent) {
        // First let's see if we can find an Enrollment for this student.
        try {
            Optional<Enrollment> optionalEnrollment = this.getEnrollments().stream()
                    .filter(enrollment -> enrollment.getStudentId() == dropStudent.getId()).findFirst();
            if(optionalEnrollment.isPresent()) {
                DatabaseManager dbManager = DatabaseManager.getInstance();
                // Update the Enrollment with the DROP status
                Enrollment enrollment = optionalEnrollment.get();
                enrollment.setStatus("DROPPED");
                enrollment.setDroppedAt(LocalDateTime.now());
                dbManager.upsert(enrollment);
                if( enrolledStudents.removeIf(student -> student.getId() == dropStudent.getId()) ) {
                    this.enrollments.remove(enrollment);
                    enrolled--;
                    // To make sure enrollment numbers get updated, could also make this a trigger in the database.
                    dbManager.upsert(this);
                    return true;
                }
            }
        } catch (SQLException | IllegalAccessException e) { e.printStackTrace(); }
        return false;
    }

    public int addToWaitlist(Student newStudent) {
        if (!isAlreadyOnWaitlist(newStudent) && !isAlreadyEnrolled(newStudent)) {
            try {
                ensureId();
                WaitlistEntry waitlist = new WaitlistEntry(newStudent.getId(), this.getId(), this.getNextWaitlistPosition());
                DatabaseManager.getInstance().upsert(waitlist);
                this.waitlist.add(waitlist);
                return waitlist.getId();
            } catch (SQLException | IllegalAccessException e) {
                return 0;
            }
        }
        return 0;
    }

    public boolean removeFromWaitlist(Student student) {
        try {
            // First let's see if we can find a WaitlistEntry for this student.
            Optional<WaitlistEntry> wle = getWaitlist().stream()
                    .filter(we -> we.getStudentId() == student.getId()).findFirst();
            if(wle.isPresent()) {
                DatabaseManager.getInstance().delete(wle);
                return waitlist.remove(wle.get());
            }
            return true;
        } catch (SQLException | IllegalAccessException e) { e.printStackTrace(); }
        return false;
    }

    public int getNextWaitlistPosition() throws SQLException {
        if(this.waitlist == null || this.waitlist.isEmpty()) {
            String sql = "SELECT count(*) FROM waitlist where section_id = ?;";
            return DatabaseManager.getInstance().executeQuery(sql, rs -> {
                return rs.getInt(1);
            }, this.getId()) + 1;
        }
        return waitlist.size() + 1; // 1-based
    }

    public int getWaitlistPosition(Student student) throws SQLException {
        if(this.waitlist == null || this.waitlist.isEmpty()) {
            String sql = "SELECT position FROM waitlist where section_id = ? and student_id = ?;";
            return DatabaseManager.getInstance().executeQuery(sql, rs -> {
                return rs.getInt(1);
            }, this.getId(), student.getId());
        }
        return waitlist.stream().filter(wl -> wl.getStudentId() == student.getId())
                .findFirst().map(WaitlistEntry::getPosition).orElse(0);
    }

    // Getters
    public int getId() { return id; }
    public String getSectionNumber() { return sectionNumber; }
    public String getSemester() { return semester; }
    public int getCapacity() { return capacity; }
    public int getEnrolled() { return enrolled; }
    public int getAvailableSeats() { return capacity - enrolled; }

    public List<WaitlistEntry> getWaitlist() throws SQLException {
        if (this.waitlist == null) {
            this.waitlist = DatabaseManager.getInstance().fetchMany(
                    WaitlistEntry.class, "section_id", this.getId());
        }
        return this.waitlist;
    }

    public String getCourseName() {
        try {
            Course c = this.getCourse();
            return (c != null) ? c.getName() : "UNKNOWN";
        } catch (SQLException se) {
            se.printStackTrace();
            return "UNKNOWN (Cause: DB ERROR)";
        }
    }

    public String getCourseCode() {
        try {
            Course course = this.getCourse();
            return course.getCode() + "-" + semester + year + "-" + sectionNumber; // CIS12-SP26-2
        } catch (SQLException e) { }
        return "UNKNOWN-" + semester + year + "-" + sectionNumber; // UNKNOWN-SP26-2
    }

    public int getCourseId() {
        return courseId;
    }

    public void setCourseId(int courseId) {
        this.courseId = courseId;
    }

    public int getYear() {
        return year;
    }

    public void setYear(int year) {
        this.year = year;
    }

    public int getFacultyId() {
        return facultyId;
    }

    public void setFacultyId(int facultyId) {
        this.facultyId = facultyId;
    }

    public String getRoom() {
        return room;
    }

    public void setRoom(String room) {
        this.room = room;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    @Override
    public String toString() {
        return String.format("%s-%s: %s %s (%d/%d enrolled)",
                courseId, sectionNumber, semester, year, enrolled, capacity);
    }

    public Course getCourse() throws SQLException {
        if (this.course == null) {
            // Lazy Load: Use the generic fetchOne from DatabaseManager
            this.course = DatabaseManager.getInstance()
                    .fetchOne(Course.class, "id", this.courseId);
        }
        return (this.course != null) ? this.course : null;
    }

    public void setCourse(Course course) {
        this.courseId = course.getId();
        this.course = course;
    }

    public List<Student> getEnrolledStudents() throws SQLException {
        if (this.enrolledStudents == null) {
            this.enrolledStudents = DatabaseManager.getInstance().fetchManyToMany(
                    Student.class, "enrollments", "section_id", "student_id", this.getId()
            );
        }
        return this.enrolledStudents;
    }

    public void setEnrolledStudents(List<Student> students) {
        this.enrolledStudents = students;
    }

    public List<Enrollment> getEnrollments() throws SQLException {
        // TODO: Gotta find a way to modify the fetch calls to take additional filters since this will return
        //   Enrollments in ANY status (i.e. DROPPED, etc.).
        if (this.enrollments == null) {
            // Lazy Load: Use the generic fetchMany from DatabaseManager
            this.enrollments = DatabaseManager.getInstance()
                    .fetchMany(Enrollment.class, "section_id", this.id);
        }
        return this.enrollments;
    }

    protected void ensureId() throws SQLException, IllegalAccessException {
        if(this.getId() == 0) {
            // If the id is not set, we need to save this object to get an id to set on the list items.
            DatabaseManager.getInstance().upsert(this);
        }
    }

    public void setEnrollments(List<Enrollment> enrollments) throws SQLException, IllegalAccessException {
        // TODO: Make the DatabaseManager even MORE generic where it can build a dependency graph of objects
        //   and make upsert/upsertAll calls to satisfy and update ids in order, rather than coding setters like this.
        ensureId();
        // Now, let's add this object's id to the related list items foreign key id
        for(Enrollment e : enrollments) { e.setSectionId(this.getId()); }
        // Now let's upsertAll of these list items (i.e. a batch) and set as this object's related field.
        DatabaseManager.getInstance().upsertAll(enrollments);
        this.enrollments = enrollments;
    }

    public Faculty getFaculty() throws SQLException {
        if (this.faculty == null) {
            // Lazy Load: Use the generic fetchOne from DatabaseManager
            this.faculty = DatabaseManager.getInstance()
                    .fetchOne(Faculty.class, "id", this.facultyId);
        }
        return (this.faculty != null) ? this.faculty : null;
    }

    public void setFaculty(Faculty faculty) {
        this.facultyId = faculty.getId();
        this.faculty = faculty;
    }
}
