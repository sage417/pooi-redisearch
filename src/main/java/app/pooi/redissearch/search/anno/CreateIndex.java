package app.pooi.redissearch.search.anno;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface CreateIndex {

    /**
     * 索引名称
     *
     * @return
     */
    String index() default "";

    /**
     * 文档id
     * @return
     */
    String documentId() default "";

    /**
     * 索引字段
     *
     * @return
     */
    Field[] fields() default {};
}

