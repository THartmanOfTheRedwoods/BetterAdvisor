package edu.advising.commands;

import edu.advising.core.*;

import java.sql.SQLException;
import java.util.List;

/**
 * ADD ANNOTATIONS during Command Pattern Week
 * -
 * Course Section - Represents a course section
 */
@Table(name = "courses")
public class Course {
    @Id(isPrimary = true)
    @Column(name = "id", upsertIgnore = true)
    private int id;
    @Id
    @Column(name = "code")
    private String code;
    @Column(name = "name")
    private String name;
    @Column(name = "description")
    private String description;
    @Column(name = "credits")
    private double credits;
    @Column(name = "department_id", foreignKey = true)
    private int departmentId;
    @Column(name = "level")
    private String level;
    @Column(name = "is_active")
    private boolean isActive;
    @OneToMany(targetEntity = Section.class, mappedBy = "course_id")
    private List<Section> sections; // Cached list of available sections.
    @ManyToOne(targetEntity = Department.class, joinColumn = "department_id")
    private Department department;

    public Course() {}

    public Course(String code, String name, String description, int credits, int departmentId, String level) {
        this.code = code;
        this.name = name;
        this.description = description;
        this.credits = credits;
        this.departmentId = departmentId;
        this.level = level;
        this.isActive = true;

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

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public double getCredits() {
        return credits;
    }

    public void setCredits(double credits) {
        this.credits = credits;
    }

    public int getDepartmentId() {
        return departmentId;
    }

    public void setDepartmentId(int departmentId) {
        this.departmentId = departmentId;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public boolean isActive() {
        return isActive;
    }

    public void setActive(boolean active) {
        isActive = active;
    }

    public List<Section> getSections() throws SQLException {
        if (this.sections == null) {
            // Lazy Load: Use the generic fetchMany from DatabaseManager
            this.sections = DatabaseManager.getInstance()
                    .fetchMany(Section.class, "course_id", this.id);
        }
        return this.sections;
    }

    protected void ensureId() throws SQLException, IllegalAccessException {
        if(this.getId() == 0) {
            // If the id is not set, we need to save this object to get an id to set on the list items.
            DatabaseManager.getInstance().upsert(this);
        }
    }

    public void setSections(List<Section> sections) throws SQLException, IllegalAccessException {
        ensureId();
        // Now, let's add this object's id to the related list items foreign key id
        for(Section s : sections) { s.setCourseId(this.getId()); }
        // Now let's upsertAll of these list items (i.e. a batch) and set as this object's related field.
        DatabaseManager.getInstance().upsertAll(sections);
        this.sections = sections;
    }

    public Department getDepartment() throws SQLException {
        if (this.department == null) {
            // Lazy Load: Use the generic fetchOne from DatabaseManager
            this.department = DatabaseManager.getInstance()
                    .fetchOne(Department.class, "id", this.departmentId);
        }
        return (this.department != null) ? this.department : null;
    }

    public void setDepartment(Department department) {
        this.department = department;
    }
}
