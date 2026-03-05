// Week 1: SINGLETON PATTERN
// Foundation: Database Connection Manager
// Features Implemented: Basic database connectivity
// Why First: Essential infrastructure that all other components will use

package edu.advising.core;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.lang.reflect.Field;
import java.util.Optional;

// TODO: Make DatabaseManager an abstract class that implements a template methods for methods like upsertAll, which use
//  an abstract method called buildUpsertSql to implement Database specific upsert sql statements, then implement
//  concrete subclasses of DatabaseManager that override and implement buildUpsertSql for specific databases,
//  (H2, MySQL, PostgreSQL, etc.)

/**
 * DatabaseManager - Singleton Pattern
 * Ensures only one database connection pool exists throughout the application.
 * This prevents connection leaks and ensures efficient resource management.
 */
public class DatabaseManager {
    private static DatabaseManager instance;
    private final HikariDataSource dataSource;

    //private static final String URL = "jdbc:h2:mem:advising;DB_CLOSE_DELAY=-1";
    private static final String URL = "jdbc:h2:file:./advising";
    private static final String USER = "admin";
    private static final String PASSWORD = "admin";

    // Private constructor prevents instantiation from other classes
    private DatabaseManager() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(URL);
        config.setUsername(USER);
        config.setPassword(PASSWORD);
        config.setDriverClassName("org.h2.Driver");

        // Pool performance tuning
        config.setMaximumPoolSize(10);
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");

        this.dataSource = new HikariDataSource(config);
        initializeDatabase();
        System.out.println("Database connection pool established");
    }

    // Thread-safe singleton instance retrieval
    public static synchronized DatabaseManager getInstance() {
        if (instance == null) {
            instance = new DatabaseManager();
        }
        return instance;
    }

    // ======================================================================================
    // CORE LAMBDA METHODS - Allows ResultSet, Connection, and P-Statement to be managed here,
    //                       but still handle data with passed in Lambda function.
    // ======================================================================================

    /**
     * Executes a query and uses a lambda to process the ResultSet.
     * The connection is automatically returned to the pool after the lambda finishes.
     */
    public <T> T executeQuery(String sql, QueryHandler<T> handler, Object... params) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            setParameters(pstmt, params);
            try (ResultSet rs = pstmt.executeQuery()) {
                return handler.handle(rs); // This is the Lambda that handles the data.
            }
        }
    }

    /**
     * Specialized helper to fetch a List of objects.
     */
    public <T> List<T> fetchList(String sql, QueryHandler<T> rowMapper, Object... params) throws SQLException {
        return executeQuery(sql, rs -> {
            List<T> results = new ArrayList<>();
            while (rs.next()) {
                results.add(rowMapper.handle(rs));
            }
            return results;
        }, params);
    }

    /**
     * Fetches a single object from the database.
     * Returns null if no record is found.
     */
    public <T> T fetch(String sql, QueryHandler<T> rowMapper, Object... params) throws SQLException {
        return executeQuery(sql, rs -> {
            if (rs.next()) {
                return rowMapper.handle(rs); // Use the same mapper logic as fetchList
            }
            return null; // Return null if the result set is empty
        }, params);
    }

    /**
     * NOTE: ADD Command Week
     * -
     * Get class inheritance hierarchy for a class.
     */
    private List<Class<?>> getTableHierarchy(Class<?> clazz) {
        List<Class<?>> hierarchy = new ArrayList<>();
        while (clazz != null && clazz.isAnnotationPresent(Table.class)) {
            hierarchy.add(0, clazz); // Add to the front to get [User, Student]
            clazz = clazz.getSuperclass();
        }
        return hierarchy;
    }

    /**
     * NOTE: ADD Observer Week
     * -
     * Get annotated fields Local to the clazz.
     *
     * @param clazz The class to inspect for annotated fields.
     */
    private List<Field> getAnnotatedFields(Class<?> clazz) {
        List<Field> columns = new ArrayList<>();
        for (Field field : clazz.getDeclaredFields()) {
            field.setAccessible(true);
            if (field.isAnnotationPresent(Column.class)) columns.add(field);
        }
        return columns;
    }

    /**
     * NOTE: ADD Command Week
     * -
     * Recursively get All annotated fields, even those inherited from Superclass(es)!
     * This is to support Superclass/Subclass hierarchies like User -> Student or User -> Faculty.
     *
     * @param clazz The class to inspect for annotated fields.
     */
    private List<Field> getAllAnnotatedFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        List<Class<?>> hierarchy = getTableHierarchy(clazz);
        for (Class<?> c : hierarchy) {
            for (Field field : c.getDeclaredFields()) {
                field.setAccessible(true);
                if (field.isAnnotationPresent(Column.class)) {
                    fields.add(field);
                }
            }
        }
        return fields;
    }

    /**
     * NOTE: ADD Observer Week
     * -
     * Only get fields that are annoted with @Id. Useful for upsert merging on Natural Key/UNIQUE constraints.
     */
    private List<Field> getIdAnnotatedFields(List<Field> allFields) {
        return allFields.stream().filter(f -> f.isAnnotationPresent(Id.class)).toList();
    }

    /**
     * NOTE: ADD Observer or Command Week
     * -
     * Gets the PRIMARY annotated @Id field of a Class, which will primarily be used in ManyToMany object joins.
     */
    private <T> String getLocalIdColumnName(Class<T> targetClass) {
        // We get the target ID column name from the @Id field of the target class
        return getAnnotatedFields(targetClass).stream()
                .filter(f -> f.isAnnotationPresent(Id.class))
                .map(f -> f.getAnnotation(Column.class).name())
                .findFirst().orElse("id");
    }

    /**
     * NOTE: ADD Observer or Command Week
     * -
     * Gets the PRIMARY annotated @Id field's name of a Class recursively to handle hierarchical classes.
     */
    private <T> String getPrimaryIdColumnName(Class<T> targetClass) {
        return getAllAnnotatedFields(targetClass).stream()
                .filter(f -> f.isAnnotationPresent(Id.class))
                .filter(f -> f.getAnnotation(Id.class).isPrimary())
                .map(f -> f.getAnnotation(Column.class).name())
                .findFirst().orElse("id");
    }

    /**
     * NOTE: ADD Command Week
     * -
     * Gets the PRIMARY annotated @Id field of a Class recursively to handle hierarchical classes.
     */
    private <T> Optional<Field> getPrimaryIdColumn(Class<T> targetClass) {
        // We get the target ID column name from the @Id field of the target class
        return getAllAnnotatedFields(targetClass).stream()
                .filter(f -> f.isAnnotationPresent(Id.class))
                .filter(f -> f.getAnnotation(Id.class).isPrimary())
                .findFirst();
    }


    /**
     * NOTE: ADD Observer Week
     * -
     * Only get fields that aren't ignored for upserts. We ignore AUTO_INCREMENT id fields, for example.
     * To ignore AUTO_INC fields though, you'll still need a UNIQUE index on the Natural Key for upsert to work.
     */
    private List<Field> getUpsertFields(List<Field> allFields, Class<?> clazz) {
        List<Field> upsertFields = new ArrayList<>();
        for (Field f : allFields) {
            Column col = f.getAnnotation(Column.class);
            boolean isIgnored = col.upsertIgnore();
            if (!isIgnored) {
                upsertFields.add(f);
            }
        }
        // Need to make sure the parent's primary Id is in this list if this is a sub-class.
        if (clazz.getAnnotation(Table.class).isSubTable()) {
            Optional<Field> oFieldPId = getPrimaryIdColumn(clazz);
            oFieldPId.ifPresent(upsertFields::add);
        }
        return upsertFields;
    }

    /**
     * NOTE: ADD Observer Week
     * -
     * Propagate's Parent Ids/Primary keys to Subclass objects during hierarchical/related table updates.
     * This allows tables like Student to get its id from the related Superclass/User INSERT/UPDATE.
     * SQLException can arise due to database queries
     * IllegalAccessException can arise due to java.lang.reflect when using annotations.
     */
    private <T> void propagateGeneratedKeys(PreparedStatement pstmt, List<T> items, List<Field> localFields)
            throws SQLException, IllegalAccessException {
        // Find the auto-increment field in this class level
        // NOTE: localFields for say User will find an autoIncField and pass on to Student Items,
        //   but Student localFields will not, and therefore not go into the isPresent conditional.
        //   Thus, this only works for 2 levels, which is liekly good enough.
        Optional<Field> autoIncField = localFields.stream()
                .filter(f -> f.getAnnotation(Column.class).upsertIgnore())
                .findFirst();

        if (autoIncField.isPresent()) {
            Field field = autoIncField.get();
            field.setAccessible(true);

            try (ResultSet rs = pstmt.getGeneratedKeys()) {
                for (T item : items) {
                    // If the object didn't have an ID, set the one the DB just made
                    Object existingId = field.get(item);
                    if (existingId == null || (existingId instanceof Number && ((Number) existingId).longValue() == 0)) {
                        if (rs.next()) {
                            field.set(item, rs.getObject(1));
                        }
                    }
                }
            }
        }
    }

    /**
     * NOTE: ADD Observer Week
     * -
     * Upserts (Inserts or Updates) a list of objects into the database.
     * Uses H2's MERGE syntax and JDBC Batching for high performance.
     * SQLException can arise due to database queries
     * IllegalAccessException can arise due to java.lang.reflect when using annotations.
     */
    public <T> void upsertAll(List<T> items) throws SQLException, IllegalAccessException {
        if (items == null || items.isEmpty()) return;

        Connection conn = dataSource.getConnection();
        try {
            conn.setAutoCommit(false); // Start Transaction

            Class<?> leafClass = items.get(0).getClass();
            List<Class<?>> hierarchy = getTableHierarchy(leafClass);

            for (Class<?> clazz : hierarchy) {
                Table tableAnn = clazz.getAnnotation(Table.class);

                // Only get fields DECLARED in this specific class (User fields vs Student fields)
                List<Field> localFields = getAnnotatedFields(clazz);

                // If this subclass has no localFields, we can safely ignore it.
                if (localFields.isEmpty()) {
                    continue;
                }

                // Handle ID Generation/Exclusion logic
                //List<Field> writeableFields = determineWriteableFields(items.get(0), localFields);
                List<Field> writeableFields = getUpsertFields(localFields, clazz);

                // List<Field> keyFields = localFields.stream().filter(f -> f.isAnnotationPresent(Id.class)).toList();
                List<Field> keyFields = getIdAnnotatedFields(writeableFields);

                String sql = buildUpsertSql(tableAnn.name(), writeableFields, keyFields);

                try (PreparedStatement pstmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                    for (T item : items) {
                        for (int i = 0; i < writeableFields.size(); i++) {
                            Field f = writeableFields.get(i);
                            Object value = f.get(item);

                            if (f.getAnnotation(Column.class).nullableforeignKey() && (value == null || (int) value == 0)) {
                                pstmt.setObject(i + 1, null); // Make sure we send a null FK
                            } else {
                                pstmt.setObject(i + 1, value);
                            }
                        }
                        pstmt.addBatch();
                    }
                    //System.out.printf("~~~~%n%s%n~~~~%n", pstmt.toString());
                    pstmt.executeBatch();

                    // ID HAND-OFF: If this was the parent table and IDs were generated,
                    // we must catch them and set them on the items for the next table (FK)
                    propagateGeneratedKeys(pstmt, items, localFields);
                }
            }
            conn.commit(); // Success!
        } catch (Exception e) {
            conn.rollback(); // Undo everything on failure
            throw new SQLException("Transaction failed. Changes rolled back.", e);
        } finally {
            conn.setAutoCommit(true);
            conn.close();
        }
    }

    protected String buildUpsertSql(String tableName, List<Field> allColumns, List<Field> keyColumns) {
        StringBuilder sql = new StringBuilder("MERGE INTO " + tableName + " (");
        StringBuilder values = new StringBuilder();
        StringBuilder keys = new StringBuilder();

        // 1. Build Column list and Value placeholders
        for (int i = 0; i < allColumns.size(); i++) {
            sql.append(String.format("`%s`", allColumns.get(i).getAnnotation(Column.class).name()));
            values.append("?");
            if (i < allColumns.size() - 1) {
                sql.append(", ");
                values.append(", ");
            }
        }

        // 2. Build the KEY clause (The columns to match on)
        for (int i = 0; i < keyColumns.size(); i++) {
            keys.append(String.format("`%s`", keyColumns.get(i).getAnnotation(Column.class).name()));
            if (i < keyColumns.size() - 1) keys.append(", ");
        }

        return sql.append(") KEY (").append(keys)
                .append(") VALUES (").append(values).append(")").toString();
    }

    /**
     * Builds the FROM clause with JOINs for the entire inheritance hierarchy.
     * Example: "students t JOIN users p ON t.id = p.id"
     */
    protected String buildJoinedFromClause(Class<?> clazz) {
        Table tableAnn = clazz.getAnnotation(Table.class);  // Child table
        String alias = "t"; // Child alias
        StringBuilder from = new StringBuilder(tableAnn.name() + " " + alias); // Initial SQL
        String childId = getPrimaryIdColumnName(clazz); // Initial primary id for joining.
        // Get the full hierarchy.
        List<Class<?>> hierarchy = getTableHierarchy(clazz);
        // Pseudo windowing function to consider 2 table hierarchies at a time, pseudo cause we ignore the Child table.
        for (int i = hierarchy.size() - 2; i >= 0; i--) {  // Minus 2 to ignore child, which is last in hierarchy.
            Class<?> parent = hierarchy.get(i);
            String parentTable = parent.getAnnotation(Table.class).name();
            String parentAlias = "p" + i;
            // Find Primary ID column for joining
            String parentId = getPrimaryIdColumnName(parent);
            // Process the pair (current, next)
            from.append(String.format(" JOIN %s %s ON %s.%s = %s.%s",
                    parentTable, parentAlias, alias, childId, parentAlias, parentId));
            // Update alias and childId so the next join uses previous parent as the new child.
            alias = parentAlias;
            childId = parentId;
        }
        return from.toString();
    }

    /**
     * NOTE: ADD Observer Week
     * -
     * Upserts a single object into the database.
     * Reuses the upsertAll logic for consistency.
     */
    public <T> void upsert(T item) throws SQLException, IllegalAccessException {
        if (item == null) return;
        upsertAll(Collections.singletonList(item));
    }

    /**
     * NOTE: ADD Command Week
     * -
     * Generic Row Mapper that uses reflection to map ResultSet columns
     * to fields annotated with @Column.
     */
    private <T> QueryHandler<T> autoMapper(Class<T> clazz) {
        List<Field> allFields = getAllAnnotatedFields(clazz); // Use the recursive version we built
        return rs -> {
            try {
                T dto = clazz.getDeclaredConstructor().newInstance();
                for (Field field : allFields) {
                    String colName = field.getAnnotation(Column.class).name();
                    try {
                        // We use rs.getObject(colName) but catch if the column isn't in the SQL result
                        Object value = rs.getObject(colName);
                        if (value != null) {
                            field.setAccessible(true);
                            // Handle java.sql.Timestamp conversions which is a special case.
                            // If other cases arise, consider redesigning and refactoring, perhaps with a HashMap.
                            if (value instanceof Timestamp) {
                                field.set(dto, ((Timestamp) value).toLocalDateTime());  // This assumes LocalDateTime
                            } else if (value instanceof Date) {
                                field.set(dto, ((Date) value).toLocalDate());  // This assumes LocalDate
                            } else {
                                field.set(dto, value);
                            }
                        }
                    } catch (SQLException e) {
                        System.out.printf("~~~ Skipped %s as its not in SQL Result ~~~%n", colName);
                    }
                }
                return dto;
            } catch (Exception e) {
                throw new SQLException("Mapping failed for: " + clazz.getSimpleName(), e);
            }
        };
    }

    /**
     * NOTE: ADD Command Week
     * -
     * Fetches a single record by a specific column value (e.g. id), and handles class hierarchies.
     * TODO: Modify fetchOne to take Optional Filter parameter allowing for additional SQL Filters.
     */
    public <T> T fetchOne(Class<T> clazz, String idColumn, Object idValue) throws SQLException {
        String joinedFrom = buildJoinedFromClause(clazz);
        // Note: We use "t." + idColumn to ensure we target the leaf table alias
        String sql = "SELECT * FROM " + joinedFrom + " WHERE t." + idColumn + " = ? LIMIT 1";
        return fetch(sql, autoMapper(clazz), idValue);
    }

    /**
     * NOTE: ADD Command Week
     * -
     * Fetches a list of records by a specific column value (e.g., Foreign Key).
     * TODO: Modify fetchMany to take Optional Filter parameter allowing for additional SQL Filters.
     */
    public <T> List<T> fetchMany(Class<T> clazz, String fkColumn, Object value) throws SQLException {
        String joinedFrom = buildJoinedFromClause(clazz);
        String sql = "SELECT * FROM " + joinedFrom + " WHERE t." + fkColumn + " = ?";
        return fetchList(sql, autoMapper(clazz), value);
    }

    /**
     * NOTE: ADD Command Week
     * -
     * Fetches a list of related objects across a Many-to-Many join table.
     * TODO: Modify fetchManyToMany to take Optional Filter parameter allowing for additional SQL Filters.
     */
    public <T> List<T> fetchManyToMany(Class<T> targetClass, String joinTable,
                                       String joinCol, String invJoinCol, Object sourceId) throws SQLException {
        String targetTable = targetClass.getAnnotation(Table.class).name();
        String targetIdCol = getPrimaryIdColumnName(targetClass);

        // INHERITANCE CHECK to handle Model Inheritance hierarchies.
        // If the parent has a @Table, we must JOIN it to handle cases like: User -> Student or User -> Faculty
        String fromClause = buildJoinedFromClause(targetClass);

        // Example: SELECT s.* FROM sections s JOIN enrollments e ON s.section_id = e.section_id WHERE e.student_id = ?
        String sql = String.format(
                "SELECT * FROM %s JOIN %s j ON t.%s = j.%s WHERE j.%s = ?",
                fromClause, joinTable, targetIdCol, invJoinCol, joinCol
        );

        return fetchList(sql, autoMapper(targetClass), sourceId);
    }

    /**
     * Deletes a single object from the database.
     * Reuses the deleteAll logic to ensure hierarchical integrity.
     */
    public <T> void delete(T item) throws SQLException, IllegalAccessException {
        if (item == null) return;
        deleteAll(Collections.singletonList(item));
    }

    /**
     * Deletes a list of objects from the database.
     * Handles class hierarchies by deleting from the most specific table (child)
     * up to the most general table (parent).
     */
    public <T> void deleteAll(List<T> items) throws SQLException, IllegalAccessException {
        if (items == null || items.isEmpty()) return;

        Connection conn = dataSource.getConnection();
        try {
            conn.setAutoCommit(false); // Start Transaction

            Class<?> leafClass = items.get(0).getClass();
            List<Class<?>> hierarchy = getTableHierarchy(leafClass);

            // IMPORTANT: We must delete in REVERSE order of insertion.
            // If hierarchy is [User, Student], we must delete from Student then User.
            List<Class<?>> reverseHierarchy = new ArrayList<>(hierarchy);
            Collections.reverse(reverseHierarchy);

            for (Class<?> clazz : reverseHierarchy) {
                Table tableAnn = clazz.getAnnotation(Table.class);
                if (tableAnn == null) continue;

                // We identify the row to delete using the Primary ID defined in the hierarchy
                String primaryKeyColName = getPrimaryIdColumnName(leafClass);
                Optional<Field> oPrimaryField = getPrimaryIdColumn(leafClass);

                if (oPrimaryField.isEmpty()) {
                    throw new SQLException("Delete failed: No primary key field found for " + leafClass.getSimpleName());
                }

                Field primaryField = oPrimaryField.get();
                primaryField.setAccessible(true);

                String sql = String.format("DELETE FROM %s WHERE %s = ?", tableAnn.name(), primaryKeyColName);

                try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    for (T item : items) {
                        Object idValue = primaryField.get(item);
                        if (idValue == null) continue; // Cannot delete a record without an ID

                        pstmt.setObject(1, idValue);
                        pstmt.addBatch();
                    }
                    pstmt.executeBatch();
                }
            }
            conn.commit(); // Success!
        } catch (Exception e) {
            conn.rollback(); // Undo everything on failure
            throw new SQLException("Delete transaction failed. Changes rolled back.", e);
        } finally {
            conn.setAutoCommit(true);
            conn.close();
        }
    }

    /**
     * Executes UPDATE, INSERT, or DELETE and returns affected rows.
     */
    public int executeUpdate(String sql, Object... params) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            setParameters(pstmt, params);
            return pstmt.executeUpdate();
        }
    }

    /**
     * Executes INSERT and returns the auto-generated ID.
     */
    public int executeInsert(String sql, Object... params) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            setParameters(pstmt, params);
            pstmt.executeUpdate();
            try (ResultSet rs = pstmt.getGeneratedKeys()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        }
    }

    // ======================================================================================
    // UTILS & LIFECYCLE
    // ======================================================================================

    private void setParameters(PreparedStatement pstmt, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            pstmt.setObject(i + 1, params[i]);
        }
    }

    public void shutdown() {
        if (dataSource != null) dataSource.close();
    }

    /**
     * Initialize complete database schema for all 14 weeks - 2 Exam weeks.
     */
    private void initializeDatabase() {
        try {
            System.out.println("Initializing database schema...");

            // ================================================================
            // WEEK 1 & 2: Core User Tables (Singleton, Factory)
            // ================================================================

            executeUpdate("CREATE TABLE IF NOT EXISTS users (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, " +
                    "username VARCHAR(50) UNIQUE NOT NULL, " +
                    "password VARCHAR(255) NOT NULL, " +  // Increased for hashed passwords
                    "user_type VARCHAR(20) NOT NULL, " +
                    "first_name VARCHAR(50) NOT NULL, " +
                    "last_name VARCHAR(50) NOT NULL, " +
                    "phone VARCHAR(20)," +
                    "email VARCHAR(100), " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "last_login TIMESTAMP, " +
                    "is_active BOOLEAN DEFAULT TRUE," +
                    "UNIQUE(email))");

            executeUpdate("CREATE TABLE IF NOT EXISTS auth_sessions (" +
                    "auth_token VARCHAR(255) PRIMARY KEY, " +
                    "user_id INT NOT NULL, " +
                    "state VARCHAR(50) NOT NULL, " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "expires_at TIMESTAMP NOT NULL, " +
                    "FOREIGN KEY (user_id) REFERENCES users(id))");

            executeUpdate("CREATE TABLE IF NOT EXISTS students (" +
                    "id INT PRIMARY KEY, " +
                    "student_id VARCHAR(20) UNIQUE NOT NULL, " +
                    "gpa DECIMAL(3,2) DEFAULT 0.00, " +
                    "enrollment_status VARCHAR(20) DEFAULT 'ACTIVE', " +
                    "academic_standing VARCHAR(20) DEFAULT 'GOOD_STANDING', " +
                    "classification VARCHAR(20), " +  // FRESHMAN, SOPHOMORE, etc.
                    "major VARCHAR(100), " +
                    "minor VARCHAR(100), " +
                    "advisor_id INT, " +
                    "FOREIGN KEY (id) REFERENCES users(id) ON DELETE CASCADE)");

            executeUpdate("CREATE TABLE IF NOT EXISTS faculty (" +
                    "id INT PRIMARY KEY, " +
                    "employee_id VARCHAR(20) UNIQUE NOT NULL, " +
                    "department VARCHAR(50), " +
                    "title VARCHAR(50), " +  // Professor, Associate Professor, etc.
                    "office_location VARCHAR(100), " +
                    "office_hours TEXT, " +
                    "hire_date DATE, " +
                    "FOREIGN KEY (id) REFERENCES users(id) ON DELETE CASCADE)");

            // ================================================================
            // WEEK 3: Authentication & Security (Strategy Pattern)
            // ================================================================

            executeUpdate("CREATE TABLE IF NOT EXISTS authentication_methods (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, " +
                    "user_id INT NOT NULL, " +
                    "method_type VARCHAR(50) NOT NULL, " +  // BASIC, SECURE, TWO_FACTOR, BIOMETRIC
                    "is_primary BOOLEAN DEFAULT FALSE, " +
                    "is_enabled BOOLEAN DEFAULT TRUE, " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE)");

            executeUpdate("CREATE TABLE IF NOT EXISTS two_factor_codes (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, " +
                    "user_id INT NOT NULL, " +
                    "code VARCHAR(10) NOT NULL, " +
                    "code_type VARCHAR(20) NOT NULL, " +  // SMS, EMAIL, AUTHENTICATOR
                    "generated_at TIMESTAMP NOT NULL, " +
                    "expires_at TIMESTAMP NOT NULL, " +
                    "used_at TIMESTAMP, " +
                    "is_used BOOLEAN DEFAULT FALSE, " +
                    "attempts INT DEFAULT 0, " +
                    "FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE)");

            executeUpdate("CREATE TABLE IF NOT EXISTS password_history (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, " +
                    "user_id INT NOT NULL, " +
                    "password_hash VARCHAR(255) NOT NULL, " +
                    "changed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "changed_by INT, " +  // Admin override capability
                    "FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE)");

            executeUpdate("CREATE TABLE IF NOT EXISTS password_reset_tokens (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, " +
                    "user_id INT NOT NULL, " +
                    "token VARCHAR(255) UNIQUE NOT NULL, " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "expires_at TIMESTAMP NOT NULL, " +
                    "used_at TIMESTAMP, " +
                    "is_used BOOLEAN DEFAULT FALSE, " +
                    "FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE)");

            executeUpdate("CREATE TABLE IF NOT EXISTS login_attempts (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, " +
                    "username VARCHAR(50) NOT NULL, " +
                    "attempt_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "status VARCHAR(20) NOT NULL, " +
                    "ip_address VARCHAR(45), " +
                    "user_agent TEXT, " +
                    "failure_reason VARCHAR(100))");

            executeUpdate("CREATE TABLE IF NOT EXISTS password_policies (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, " +
                    "policy_name VARCHAR(50) UNIQUE NOT NULL, " +
                    "min_length INT DEFAULT 8, " +
                    "require_uppercase BOOLEAN DEFAULT TRUE, " +
                    "require_lowercase BOOLEAN DEFAULT TRUE, " +
                    "require_digit BOOLEAN DEFAULT TRUE, " +
                    "require_special BOOLEAN DEFAULT TRUE, " +
                    "max_age_days INT DEFAULT 90, " +
                    "history_count INT DEFAULT 5, " +  // Can't reuse last 5 passwords
                    "is_active BOOLEAN DEFAULT TRUE)");

            // ================================================================
            // WEEK 4: Notifications (Observer Pattern)
            // ================================================================

            executeUpdate("CREATE TABLE IF NOT EXISTS notifications (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, " +
                    "user_id INT NOT NULL, " +
                    "`type` VARCHAR(50) NOT NULL, " +  // GRADE_CHANGE, REGISTRATION, PAYMENT, etc.
                    "message TEXT NOT NULL, " +
                    "priority VARCHAR(20) DEFAULT 'MEDIUM', " +  // HIGH, MEDIUM, LOW
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "read_at TIMESTAMP, " +
                    "read_status BOOLEAN DEFAULT FALSE, " +
                    "deleted_at TIMESTAMP, " +
                    "metadata TEXT, " +  // JSON for additional data
                    "FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE)");

            executeUpdate("CREATE TABLE IF NOT EXISTS notification_preferences (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, " +
                    "user_id INT NOT NULL, " +
                    "notification_type VARCHAR(50) NOT NULL, " +
                    "email_enabled BOOLEAN DEFAULT TRUE, " +
                    "sms_enabled BOOLEAN DEFAULT FALSE, " +
                    "push_enabled BOOLEAN DEFAULT TRUE, " +
                    "frequency VARCHAR(20) DEFAULT 'IMMEDIATE', " +  // IMMEDIATE, DIGEST, DISABLED
                    "FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE, " +
                    "UNIQUE(user_id, notification_type))");

            // ================================================================
            // WEEK 5: Commands & Transactions (Command Pattern)
            // ================================================================

            executeUpdate("CREATE TABLE IF NOT EXISTS command_history (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, " +
                    "user_id INT NOT NULL, " +
                    "command_type VARCHAR(50) NOT NULL, " +  // REGISTER, DROP, PAYMENT, etc.
                    "command_data TEXT, " +  // JSON serialized command
                    "executed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "undone_at TIMESTAMP, " +
                    "is_undone BOOLEAN DEFAULT FALSE, " +
                    "success BOOLEAN NOT NULL, " +
                    "error_message TEXT, " +
                    "FOREIGN KEY (user_id) REFERENCES users(id))");

            // ================================================================
            // WEEK 5-11: Course Management (Multiple Patterns)
            // ================================================================

            executeUpdate("CREATE TABLE IF NOT EXISTS departments (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, " +
                    "code VARCHAR(10) UNIQUE NOT NULL, " +
                    "name VARCHAR(100) NOT NULL, " +
                    "chair_id INT, " +
                    "budget DECIMAL(12,2), " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "FOREIGN KEY (chair_id) REFERENCES faculty(id))");

            executeUpdate("CREATE TABLE IF NOT EXISTS programs (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, " +
                    "code VARCHAR(20) UNIQUE NOT NULL, " +
                    "name VARCHAR(100) NOT NULL, " +
                    "degree_type VARCHAR(20) NOT NULL, " +  // BS, BA, MS, MA, PhD
                    "department_id INT NOT NULL, " +
                    "total_credits_required INT DEFAULT 120, " +
                    "is_active BOOLEAN DEFAULT TRUE, " +
                    "FOREIGN KEY (department_id) REFERENCES departments(id))");

            executeUpdate("CREATE TABLE IF NOT EXISTS courses (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, " +
                    "code VARCHAR(20) UNIQUE NOT NULL, " +
                    "name VARCHAR(100) NOT NULL, " +
                    "description TEXT, " +
                    "credits DOUBLE NOT NULL, " +
                    "department_id INT NOT NULL, " +
                    "level VARCHAR(20), " +  // UNDERGRADUATE, GRADUATE
                    "is_active BOOLEAN DEFAULT TRUE, " +
                    "FOREIGN KEY (department_id) REFERENCES departments(id))");

            executeUpdate("CREATE TABLE IF NOT EXISTS course_prerequisites (" +
                    "course_id INT NOT NULL, " +
                    "prerequisite_id INT NOT NULL, " +
                    "is_corequisite BOOLEAN DEFAULT FALSE, " +
                    "PRIMARY KEY (course_id, prerequisite_id), " +
                    "FOREIGN KEY (course_id) REFERENCES courses(id), " +
                    "FOREIGN KEY (prerequisite_id) REFERENCES courses(id))");

            executeUpdate("CREATE TABLE IF NOT EXISTS sections (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, " +
                    "course_id INT NOT NULL, " +
                    "section_number VARCHAR(10) NOT NULL, " +
                    "semester VARCHAR(20) NOT NULL, " +
                    "`year` INT NOT NULL, " +
                    "capacity INT NOT NULL, " +
                    "enrolled INT DEFAULT 0, " +
                    "faculty_id INT, " +
                    "room VARCHAR(50), " +
                    "status VARCHAR(20) DEFAULT 'OPEN', " +  // OPEN, CLOSED, CANCELLED
                    "FOREIGN KEY (course_id) REFERENCES courses(id), " +
                    "FOREIGN KEY (faculty_id) REFERENCES faculty(id), " +
                    "UNIQUE(course_id, section_number, semester, `year`))");

            executeUpdate("CREATE TABLE IF NOT EXISTS section_meeting_times (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, " +
                    "section_id INT NOT NULL, " +
                    "day_of_week VARCHAR(10) NOT NULL, " +
                    "start_time TIME NOT NULL, " +
                    "end_time TIME NOT NULL, " +
                    "room VARCHAR(50), " +
                    "FOREIGN KEY (section_id) REFERENCES sections(id) ON DELETE CASCADE)");

            // ================================================================
            // WEEK 6: Enrollment & State (State Pattern)
            // ================================================================

            executeUpdate("CREATE TABLE IF NOT EXISTS enrollments (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, " +
                    "student_id INT NOT NULL, " +
                    "section_id INT NOT NULL, " +
                    "enrollment_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "status VARCHAR(20) NOT NULL, " +  // ENROLLED, DROPPED, WITHDRAWN, COMPLETED
                    "grade VARCHAR(5), " +
                    "grade_points DECIMAL(3,2), " +
                    "midterm_grade VARCHAR(5), " +
                    "final_grade VARCHAR(5), " +
                    "graded_at TIMESTAMP, " +
                    "dropped_at TIMESTAMP, " +
                    "drop_reason TEXT, " +
                    "FOREIGN KEY (student_id) REFERENCES students(id), " +
                    "FOREIGN KEY (section_id) REFERENCES sections(id), " +
                    "UNIQUE(student_id, section_id))");

            executeUpdate("CREATE TABLE IF NOT EXISTS waitlist (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, " +
                    "student_id INT NOT NULL, " +
                    "section_id INT NOT NULL, " +
                    "position INT NOT NULL, " +
                    "added_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "removed_date TIMESTAMP, " +
                    "status VARCHAR(20) DEFAULT 'ACTIVE', " +  // ACTIVE, ENROLLED, REMOVED
                    "notification_sent BOOLEAN DEFAULT FALSE, " +
                    "FOREIGN KEY (student_id) REFERENCES students(id), " +
                    "FOREIGN KEY (section_id) REFERENCES sections(id), " +
                    "UNIQUE(student_id, section_id))");

            executeUpdate("CREATE TABLE IF NOT EXISTS registration_periods (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, " +
                    "semester VARCHAR(20) NOT NULL, " +
                    "`year` INT NOT NULL, " +
                    "open_date TIMESTAMP NOT NULL, " +
                    "close_date TIMESTAMP NOT NULL, " +
                    "late_registration_end TIMESTAMP, " +
                    "current_state VARCHAR(20) NOT NULL, " +  // NOT_OPEN, OPEN, LATE, CLOSED
                    "UNIQUE(semester, `year`))");

            executeUpdate("CREATE TABLE IF NOT EXISTS transcript_requests (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, " +
                    "student_id INT NOT NULL, " +
                    "request_type VARCHAR(20) NOT NULL, " +  // OFFICIAL, UNOFFICIAL
                    "recipient_name VARCHAR(100), " +
                    "recipient_address TEXT, " +
                    "request_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "status VARCHAR(20) NOT NULL, " +  // PENDING, PROCESSING, READY, SENT, CANCELLED, FAILED
                    "tracking_number VARCHAR(50), " +
                    "fee DECIMAL(6,2), " +
                    "is_rush BOOLEAN DEFAULT FALSE, " +
                    "completed_date TIMESTAMP, " +
                    "FOREIGN KEY (student_id) REFERENCES students(id))");

            // ================================================================
            // WEEK 7: Permissions & Restrictions (Decorator Pattern)
            // ================================================================

            executeUpdate("CREATE TABLE IF NOT EXISTS user_roles (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, " +
                    "user_id INT NOT NULL, " +
                    "role_name VARCHAR(50) NOT NULL, " +  // STUDENT, FACULTY, HONORS, ATHLETE, etc.
                    "granted_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "expires_at TIMESTAMP, " +
                    "granted_by INT, " +
                    "is_active BOOLEAN DEFAULT TRUE, " +
                    "FOREIGN KEY (user_id) REFERENCES users(id), " +
                    "UNIQUE(user_id, role_name))");

            executeUpdate("CREATE TABLE IF NOT EXISTS permissions (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, " +
                    "role_name VARCHAR(50) NOT NULL, " +
                    "feature_code VARCHAR(50) NOT NULL, " +
                    "can_access BOOLEAN DEFAULT TRUE, " +
                    "UNIQUE(role_name, feature_code))");

            executeUpdate("CREATE TABLE IF NOT EXISTS permission_grants (" +
                    "id  INT AUTO_INCREMENT PRIMARY KEY," +
            "faculty_id  INT NOT NULL," +
            "student_id  INT NOT NULL," +
            "section_id  INT NOT NULL," +
            "granted_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
            "expires_at  TIMESTAMP NOT NULL," +
            "is_used     BOOLEAN DEFAULT FALSE," +
            "used_at     TIMESTAMP," +
            "is_active   BOOLEAN DEFAULT TRUE," +
            "notes       TEXT," +
            "FOREIGN KEY (faculty_id) REFERENCES faculty(id)," +
            "FOREIGN KEY (student_id) REFERENCES students(id)," +
            "FOREIGN KEY (section_id) REFERENCES sections(id)," +
            "UNIQUE (student_id, section_id))");

            executeUpdate("CREATE TABLE IF NOT EXISTS restrictions (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, " +
                    "student_id INT NOT NULL, " +
                    "restriction_type VARCHAR(50) NOT NULL, " +  // FINANCIAL_HOLD, ACADEMIC_PROBATION, etc.
                    "description TEXT, " +
                    "start_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "end_date TIMESTAMP, " +
                    "amount DECIMAL(10,2), " +  // For financial holds
                    "is_active BOOLEAN DEFAULT TRUE, " +
                    "created_by INT, " +
                    "FOREIGN KEY (student_id) REFERENCES students(id))");

            executeUpdate("CREATE TABLE IF NOT EXISTS restriction_impacts (" +
                    "restriction_type VARCHAR(50) NOT NULL, " +
                    "blocked_feature VARCHAR(50) NOT NULL, " +
                    "PRIMARY KEY(restriction_type, blocked_feature))");

            // ================================================================
            // WEEK 5 & 8: Financial Management (Command, Template Patterns)
            // ================================================================

            executeUpdate("CREATE TABLE IF NOT EXISTS payments (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, " +
                    "student_id INT NOT NULL, " +
                    "amount DECIMAL(10,2) NOT NULL, " +
                    "payment_type VARCHAR(50) NOT NULL, " +  // TUITION, FEE, HOUSING, etc.
                    "payment_method VARCHAR(50), " +  // CREDIT_CARD, CHECK, CASH, etc.
                    "payment_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "status VARCHAR(20) NOT NULL, " +  // COMPLETED, PENDING, FAILED, REFUNDED
                    "transaction_id VARCHAR(100), " +
                    "reference_number VARCHAR(100), " +
                    "processed_by INT, " +
                    "notes TEXT, " +
                    "FOREIGN KEY (student_id) REFERENCES students(id))");

            executeUpdate("CREATE TABLE IF NOT EXISTS payment_plans (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, " +
                    "student_id INT NOT NULL, " +
                    "total_amount DECIMAL(10,2) NOT NULL, " +
                    "installments INT NOT NULL, " +
                    "amount_per_installment DECIMAL(10,2) NOT NULL, " +
                    "start_date DATE NOT NULL, " +
                    "status VARCHAR(20) DEFAULT 'ACTIVE', " +  // ACTIVE, COMPLETED, DEFAULTED
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "FOREIGN KEY (student_id) REFERENCES students(id))");

            executeUpdate("CREATE TABLE IF NOT EXISTS payment_plan_installments (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, " +
                    "plan_id INT NOT NULL, " +
                    "installment_number INT NOT NULL, " +
                    "due_date DATE NOT NULL, " +
                    "amount DECIMAL(10,2) NOT NULL, " +
                    "paid_date DATE, " +
                    "paid_amount DECIMAL(10,2), " +
                    "status VARCHAR(20) DEFAULT 'PENDING', " +  // PENDING, PAID, OVERDUE
                    "FOREIGN KEY (plan_id) REFERENCES payment_plans(id), " +
                    "UNIQUE(plan_id, installment_number))");

            executeUpdate("CREATE TABLE IF NOT EXISTS student_accounts (" +
                    "student_id INT PRIMARY KEY, " +
                    "current_balance DECIMAL(10,2) DEFAULT 0.00, " +
                    "total_charges DECIMAL(10,2) DEFAULT 0.00, " +
                    "total_payments DECIMAL(10,2) DEFAULT 0.00, " +
                    "total_aid DECIMAL(10,2) DEFAULT 0.00, " +
                    "last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "FOREIGN KEY (student_id) REFERENCES students(id))");

            executeUpdate("CREATE TABLE IF NOT EXISTS financial_aid (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, " +
                    "student_id INT NOT NULL, " +
                    "aid_type VARCHAR(50) NOT NULL, " +  // GRANT, LOAN, SCHOLARSHIP, WORK_STUDY
                    "aid_name VARCHAR(100) NOT NULL, " +
                    "amount DECIMAL(10,2) NOT NULL, " +
                    "semester VARCHAR(20) NOT NULL, " +
                    "`year` INT NOT NULL, " +
                    "status VARCHAR(20) NOT NULL, " +  // PENDING, APPROVED, DISBURSED, DENIED
                    "application_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "approval_date TIMESTAMP, " +
                    "disbursement_date TIMESTAMP, " +
                    "FOREIGN KEY (student_id) REFERENCES students(id))");

            // ================================================================
            // WEEK 8: Reports (Template Pattern)
            // ================================================================

            executeUpdate("CREATE TABLE IF NOT EXISTS report_generations (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, " +
                    "user_id INT NOT NULL, " +
                    "report_type VARCHAR(50) NOT NULL, " +  // TRANSCRIPT, FINANCIAL, TAX, ROSTER
                    "report_format VARCHAR(20), " +  // PDF, HTML, EXCEL, CSV
                    "generated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "file_path VARCHAR(255), " +
                    "file_size INT, " +
                    "parameters TEXT, " +  // JSON
                    "FOREIGN KEY (user_id) REFERENCES users(id))");

            // ================================================================
            // WEEK 11: Budget Management (Composite Pattern)
            // ================================================================

            executeUpdate("CREATE TABLE IF NOT EXISTS budgets (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, " +
                    "name VARCHAR(100) NOT NULL, " +
                    "parent_budget_id INT, " +  // For hierarchical budgets
                    "budget_type VARCHAR(50) NOT NULL, " +  // DEPARTMENT, RESEARCH, TEACHING, etc.
                    "owner_id INT, " +  // Faculty ID
                    "fiscal_year VARCHAR(10) NOT NULL, " +
                    "allocated_amount DECIMAL(12,2) NOT NULL, " +
                    "spent_amount DECIMAL(12,2) DEFAULT 0.00, " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "FOREIGN KEY (parent_budget_id) REFERENCES budgets(id), " +
                    "FOREIGN KEY (owner_id) REFERENCES faculty(id))");

            executeUpdate("CREATE TABLE IF NOT EXISTS budget_expenses (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, " +
                    "budget_id INT NOT NULL, " +
                    "description VARCHAR(255) NOT NULL, " +
                    "amount DECIMAL(10,2) NOT NULL, " +
                    "expense_date DATE NOT NULL, " +
                    "category VARCHAR(50), " +
                    "receipt_number VARCHAR(100), " +
                    "approved_by INT, " +
                    "FOREIGN KEY (budget_id) REFERENCES budgets(id), " +
                    "FOREIGN KEY (approved_by) REFERENCES faculty(id))");

            // ================================================================
            // WEEK 11: Program Requirements (Composite Pattern)
            // ================================================================

            executeUpdate("CREATE TABLE IF NOT EXISTS program_requirements (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, " +
                    "program_id INT NOT NULL, " +
                    "requirement_group VARCHAR(100) NOT NULL, " +  // CORE, ELECTIVES, GEN_ED
                    "parent_group_id INT, " +  // For nested groups
                    "min_courses INT, " +
                    "min_credits DOUBLE, " +
                    "display_order INT DEFAULT 0, " +
                    "FOREIGN KEY (program_id) REFERENCES programs(id), " +
                    "FOREIGN KEY (parent_group_id) REFERENCES program_requirements(id))");

            executeUpdate("CREATE TABLE IF NOT EXISTS requirement_courses (" +
                    "requirement_id INT NOT NULL, " +
                    "course_id INT NOT NULL, " +
                    "is_required BOOLEAN DEFAULT TRUE, " +  // FALSE for elective choices
                    "PRIMARY KEY (requirement_id, course_id), " +
                    "FOREIGN KEY (requirement_id) REFERENCES program_requirements(id), " +
                    "FOREIGN KEY (course_id) REFERENCES courses(id))");

            // ================================================================
            // WEEK 10: External System Integration (Adapter Pattern)
            // ================================================================

            executeUpdate("CREATE TABLE IF NOT EXISTS external_transactions (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, " +
                    "transaction_type VARCHAR(50) NOT NULL, " +  // PAYMENT, TRANSCRIPT, etc.
                    "external_system VARCHAR(50) NOT NULL, " +  // NBS, NSC, etc.
                    "request_data TEXT, " +  // JSON
                    "response_data TEXT, " +  // JSON
                    "external_id VARCHAR(100), " +
                    "status VARCHAR(20) NOT NULL, " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "completed_at TIMESTAMP, " +
                    "error_message TEXT)");

            // ================================================================
            // WEEK 14: Audit Trail (Pipeline Pattern)
            // ================================================================

            executeUpdate("CREATE TABLE IF NOT EXISTS validation_logs (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, " +
                    "request_id VARCHAR(100) NOT NULL, " +
                    "request_type VARCHAR(50) NOT NULL, " +
                    "user_id INT NOT NULL, " +
                    "handler_name VARCHAR(100) NOT NULL, " +
                    "handler_order INT NOT NULL, " +
                    "validation_result VARCHAR(20) NOT NULL, " +  // PASSED, FAILED, WARNING
                    "error_message TEXT, " +
                    "metadata TEXT, " +  // JSON
                    "timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "FOREIGN KEY (user_id) REFERENCES users(id))");

            executeUpdate("CREATE TABLE IF NOT EXISTS system_audit_log (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, " +
                    "user_id INT, " +
                    "action VARCHAR(100) NOT NULL, " +
                    "entity_type VARCHAR(50), " +  // USER, COURSE, ENROLLMENT, etc.
                    "entity_id INT, " +
                    "old_value TEXT, " +  // JSON
                    "new_value TEXT, " +  // JSON
                    "ip_address VARCHAR(45), " +
                    "timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "FOREIGN KEY (user_id) REFERENCES users(id))");

            // ================================================================
            // INDEXES for Performance
            // ================================================================

            executeUpdate("CREATE INDEX IF NOT EXISTS idx_users_username ON users(username)");
            executeUpdate("CREATE INDEX IF NOT EXISTS idx_users_type ON users(user_type)");
            executeUpdate("CREATE INDEX IF NOT EXISTS idx_students_student_id ON students(student_id)");
            executeUpdate("CREATE INDEX IF NOT EXISTS idx_faculty_employee_id ON faculty(employee_id)");
            executeUpdate("CREATE INDEX IF NOT EXISTS idx_enrollments_student ON enrollments(student_id)");
            executeUpdate("CREATE INDEX IF NOT EXISTS idx_enrollments_section ON enrollments(section_id)");
            executeUpdate("CREATE INDEX IF NOT EXISTS idx_enrollments_status ON enrollments(status)");
            executeUpdate("CREATE INDEX IF NOT EXISTS idx_notifications_user ON notifications(user_id)");
            executeUpdate("CREATE INDEX IF NOT EXISTS idx_notifications_read ON notifications(read_status)");
            executeUpdate("CREATE INDEX IF NOT EXISTS idx_notifications_type ON notifications(`type`)");
            executeUpdate("CREATE INDEX IF NOT EXISTS idx_payments_student ON payments(student_id)");
            executeUpdate("CREATE INDEX IF NOT EXISTS idx_sections_semester ON sections(semester, `year`)");
            executeUpdate("CREATE INDEX IF NOT EXISTS idx_waitlist_section ON waitlist(section_id)");
            executeUpdate("CREATE INDEX IF NOT EXISTS idx_restrictions_student ON restrictions(student_id)");
            executeUpdate("CREATE INDEX IF NOT EXISTS idx_restrictions_active ON restrictions(is_active)");
            executeUpdate("CREATE INDEX IF NOT EXISTS idx_perm_grants_student ON permission_grants(student_id)");
            executeUpdate("CREATE INDEX IF NOT EXISTS idx_perm_grants_section ON permission_grants(section_id)");
            executeUpdate("CREATE INDEX IF NOT EXISTS idx_perm_grants_active ON permission_grants(is_active)");

            System.out.println("✓ Database schema initialized successfully");
            System.out.println("  Total tables created: 40+");

        } catch (SQLException e) {
            throw new RuntimeException("Error initializing database schema", e);
        }
    }

    /**
     * Insert default/seed data for testing
     */
    public void seedDatabase() {
        try {
            System.out.println("Seeding database with default data...");

            // Default password policy
            executeInsert("INSERT INTO password_policies (policy_name, min_length, " +
                    "require_uppercase, require_lowercase, require_digit, require_special, " +
                    "max_age_days, history_count) VALUES " +
                    "('DEFAULT', 8, TRUE, TRUE, TRUE, TRUE, 90, 5)");

            // Default permissions for roles
            executeInsert("INSERT INTO permissions (role_name, feature_code) VALUES " +
                    "('STUDENT', 'REGISTER_COURSES'), " +
                    "('STUDENT', 'VIEW_GRADES'), " +
                    "('STUDENT', 'MAKE_PAYMENT'), " +
                    "('STUDENT', 'VIEW_TRANSCRIPT'), " +
                    "('FACULTY', 'VIEW_CLASS_ROSTER'), " +
                    "('FACULTY', 'ENTER_GRADES'), " +
                    "('FACULTY', 'DROP_STUDENTS'), " +
                    "('HONORS', 'PRIORITY_REGISTRATION'), " +
                    "('HONORS', 'OVERLOAD_CREDITS')");

            // Default restriction impacts. Using Update instead of Insert because this table doesn't have generated
            // keys
            executeUpdate("INSERT INTO restriction_impacts (restriction_type, blocked_feature) VALUES " +
                    "('FINANCIAL_HOLD', 'REGISTER_COURSES'), " +
                    "('FINANCIAL_HOLD', 'VIEW_TRANSCRIPT'), " +
                    "('FINANCIAL_HOLD', 'ORDER_TRANSCRIPT'), " +
                    "('ACADEMIC_PROBATION', 'HONORS_PROGRAMS'), " +
                    "('ACADEMIC_PROBATION', 'STUDY_ABROAD'), " +
                    "('ACADEMIC_PROBATION', 'OVERLOAD_CREDITS')");

            // Sample department
            executeInsert("INSERT INTO departments (code, name) VALUES " +
                    "('CS', 'Computer Science'), " +
                    "('MATH', 'Mathematics'), " +
                    "('ENG', 'English')");

            System.out.println("✓ Database seeded successfully");

        } catch (SQLException e) {
            e.printStackTrace();
            System.err.println("Error seeding database: " + e.getMessage());
        }
    }
}