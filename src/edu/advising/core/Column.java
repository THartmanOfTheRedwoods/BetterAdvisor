package edu.advising.core;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Column {
    String name();
    // Allows me to handle foreignKey columns in UPSERTS when they're null or 0
    boolean nullableforeignKey() default false;
    // Allows me to handle foreignKey columns in UPSERTS differently
    boolean foreignKey() default false;
    boolean upsertIgnore() default false; // Allows me to ignore Primary id fields for UPSERTS
}
