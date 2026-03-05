package edu.advising.commands;

import edu.advising.core.*;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

@Table(name = "departments")
public class Department {
    @Id(isPrimary = true)
    @Column(name = "id", upsertIgnore = true)
    private int id;
    @Id
    @Column(name = "code")
    private String code;
    @Column(name = "name")
    private String name;
    @Column(name = "chair_id")  // References User/Faculty id
    private int chairId;
    @Column(name = "budget")
    private double budget;
    @Column(name = "created_at")
    private LocalDateTime createdAt;
    @OneToMany(targetEntity = Course.class, mappedBy = "department_id")
    private List<Course> courses; // Cached list of available courses.

    public Department() {}

    private Department(int id, String code, String name) {
        this.id = id;
        this.code = code;
        this.name = name;
    }

    public Department(String code, String name) {
        this(0, code, name);
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getChairId() {
        return chairId;
    }

    public void setChairId(int chairId) {
        this.chairId = chairId;
    }

    public double getBudget() {
        return budget;
    }

    public void setBudget(double budget) {
        this.budget = budget;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public List<Course> getCourses() throws SQLException {
        if (this.courses == null) {
            // Lazy Load: Use the generic fetchMany from DatabaseManager
            this.courses = DatabaseManager.getInstance()
                    .fetchMany(Course.class, "department_id", this.id);
        }
        return this.courses;
    }

    public void setCourses(List<Course> courses) {
        this.courses = courses;
    }
}
