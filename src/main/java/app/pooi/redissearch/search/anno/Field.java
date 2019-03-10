package app.pooi.redissearch.search.anno;

public @interface Field {

    String propertyName();

    String value() default "";

    boolean sort() default false;
}
