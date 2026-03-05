package edu.advising.commands;

import edu.advising.core.*;
import edu.advising.users.Student;

import java.sql.SQLException;
import java.time.LocalDateTime;

@Table(name = "waitlist")
public class WaitlistEntry {
    @Id(isPrimary = true)
    @Column(name = "id", upsertIgnore = true)
    private int id;
    @Id
    @Column(name = "student_id", foreignKey = true)
    private int studentId;
    @Id
    @Column(name = "section_id", foreignKey = true)
    private int sectionId;
    @Column(name = "position")
    private int position;
    @Column(name = "added_date")
    private LocalDateTime addedDate;
    @Column(name = "removed_date")
    private LocalDateTime removedDate;
    @Column(name = "status")
    private String status;
    @Column(name = "notification_sent")
    private boolean notificationSent;

    @ManyToOne(targetEntity = Section.class, joinColumn = "section_id")
    private Section section;
    @ManyToOne(targetEntity = Student.class, joinColumn = "student_id")
    private Student student;

    public WaitlistEntry() {}

    public WaitlistEntry(int studentId, int sectionId, int position) {
        this(studentId, sectionId);
        this.position = position;
    }
    public WaitlistEntry(int studentId, int sectionId) {
        this.studentId = studentId;
        this.sectionId = sectionId;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public int getStudentId() {
        return studentId;
    }

    public void setStudentId(int studentId) {
        this.studentId = studentId;
    }

    public int getSectionId() {
        return sectionId;
    }

    public void setSectionId(int sectionId) {
        this.sectionId = sectionId;
    }

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    public LocalDateTime getAddedDate() {
        return addedDate;
    }

    public void setAddedDate(LocalDateTime addedDate) {
        this.addedDate = addedDate;
    }

    public LocalDateTime getRemovedDate() {
        return removedDate;
    }

    public void setRemovedDate(LocalDateTime removedDate) {
        this.removedDate = removedDate;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public boolean isNotificationSent() {
        return notificationSent;
    }

    public void setNotificationSent(boolean notificationSent) {
        this.notificationSent = notificationSent;
    }

    public Student getStudent() throws SQLException {
        if (this.student == null) {
            // Lazy Load: Use the generic fetchOne from DatabaseManager
            this.student = DatabaseManager.getInstance()
                    .fetchOne(Student.class, "id", this.studentId);
        }
        return (this.student != null) ? this.student : null;
    }

    public void setStudent(Student student) {
        this.studentId = student.getId();
        this.student = student;
    }
}
