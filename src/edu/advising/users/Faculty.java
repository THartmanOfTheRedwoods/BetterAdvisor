package edu.advising.users;

import edu.advising.commands.Enrollment;
import edu.advising.commands.Section;
import edu.advising.core.*;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

/**
 * Faculty - Concrete user type
 */
@Table(name = "faculty", isSubTable = true)
public class Faculty extends User {
    @Id
    @Column(name="employee_id")
    private String employeeId;
    @Column(name="department")
    private String department;
    @Column(name="title")
    private String title;  // Professor, Associate Professor, etc.
    @Column(name="office_location")
    private String officeLocation;
    @Column(name="office_hours")
    private String officeHours;
    @Column(name="hire_date")
    private LocalDate hireDate;

    @OneToMany(targetEntity = Section.class, mappedBy = "faculty_id")
    private List<Section> sections;

    public Faculty() {}

    public Faculty(String username, String password, String email,
                   String firstName, String lastName, String employeeId, String department) {
        super(username, password, email, firstName, lastName);
        this.userType = "FACULTY";
        this.employeeId = employeeId;
        this.department = department;
    }

    @Override
    public void showDashboard() {
        System.out.println("\n=== FACULTY DASHBOARD ===");
        System.out.println("Employee ID: " + employeeId);
        System.out.println("Name: " + firstName + " " + lastName);
        System.out.println("Department: " + department);
        System.out.println("\nAvailable Features:");
        System.out.println("- View Class Roster");
        System.out.println("- Enter Grades");
        System.out.println("- View Schedule");
        System.out.println("- Drop Students");
    }

    // Getters
    public String getEmployeeId() {
        return employeeId;
    }

    public String getDepartment() {
        return department;
    }

    // Setters
    public void setEmployeeId(String employeeId) {
        this.employeeId = employeeId;
    }

    public void setDepartment(String department) {
        this.department = department;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getOfficeLocation() {
        return officeLocation;
    }

    public void setOfficeLocation(String officeLocation) {
        this.officeLocation = officeLocation;
    }

    public String getOfficeHours() {
        return officeHours;
    }

    public void setOfficeHours(String officeHours) {
        this.officeHours = officeHours;
    }

    public LocalDate getHireDate() {
        return hireDate;
    }

    public void setHireDate(LocalDate hireDate) {
        this.hireDate = hireDate;
    }

    public List<Section> getSections() throws SQLException {
        if (this.sections == null) {
            // Lazy Load: Use the generic fetchMany from DatabaseManager
            this.sections = DatabaseManager.getInstance()
                    .fetchMany(Section.class, "faculty_id", this.id);
        }
        return this.sections;
    }

    public void setSections(List<Section> sections) throws SQLException, IllegalAccessException{
        if(this.getId() == 0) {
            // We need to save this object to get an id to set on the list items.
            DatabaseManager.getInstance().upsert(this);
        }
        // Now, let's add this object's id to the related list items foreign key id
        for(Section s : sections) { s.setFacultyId(this.getId()); }
        // Now let's upsertAll of these list items (i.e. a batch) and set as this object's related field.
        DatabaseManager.getInstance().upsertAll(sections);
        this.sections = sections;
    }
}
